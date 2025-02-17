package com.linkedin.venice.acl.handler;

import com.linkedin.venice.acl.AclCreationDeletionListener;
import com.linkedin.venice.acl.AclException;
import com.linkedin.venice.acl.DynamicAccessController;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.exceptions.VeniceNoStoreException;
import com.linkedin.venice.meta.QueryAction;
import com.linkedin.venice.meta.ReadOnlyStoreRepository;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.protocols.VeniceClientRequest;
import com.linkedin.venice.utils.NettyUtils;
import com.linkedin.venice.utils.SslUtils;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Store-level access control handler, which is being used by both Router and Server.
 */
@ChannelHandler.Sharable
public class StoreAclHandler extends SimpleChannelInboundHandler<HttpRequest> implements ServerInterceptor {
  private static final Logger LOGGER = LogManager.getLogger(StoreAclHandler.class);

  private final ReadOnlyStoreRepository metadataRepository;
  private final DynamicAccessController accessController;

  public StoreAclHandler(DynamicAccessController accessController, ReadOnlyStoreRepository metadataRepository) {
    this.metadataRepository = metadataRepository;
    this.accessController = accessController
        .init(metadataRepository.getAllStores().stream().map(Store::getName).collect(Collectors.toList()));
    this.metadataRepository.registerStoreDataChangedListener(new AclCreationDeletionListener(accessController));
  }

  /**
   * Extract the store name from the incoming resource name.
   */
  protected String extractStoreName(String resourceName) {
    return resourceName;
  }

  protected X509Certificate extractClientCert(ChannelHandlerContext ctx) throws SSLPeerUnverifiedException {
    SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
    if (sslHandler == null) {
      /**
       * In HTTP/2, the SSLHandler is k  parent channel pipeline and the child channels won't have the SSL Handler.
       */
      sslHandler = ctx.channel().parent().pipeline().get(SslHandler.class);
    }
    return SslUtils.getX509Certificate(sslHandler.engine().getSession().getPeerCertificates()[0]);
  }

  protected X509Certificate extractClientCert(ServerCall<?, ?> call) throws SSLPeerUnverifiedException {
    SSLSession sslSession = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
    if (sslSession == null) {
      throw new SSLPeerUnverifiedException("SSL session not found");
    }

    return SslUtils.getX509Certificate(sslSession.getPeerCertificates()[0]);
  }

  /**
   * Verify if client has permission to access.
   *
   * @param ctx
   * @param req
   * @throws SSLPeerUnverifiedException
   */
  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpRequest req) throws SSLPeerUnverifiedException {
    X509Certificate clientCert = extractClientCert(ctx);

    String uri = req.uri();
    // Parse resource type and store name
    String[] requestParts = URI.create(uri).getPath().split("/");
    // invalid request if requestParts.length < 3 except for HEALTH check from venice client
    if (requestParts.length < 3
        && !(requestParts.length == 2 && requestParts[1].toUpperCase().equals(QueryAction.HEALTH.toString()))) {
      NettyUtils.setupResponseAndFlush(
          HttpResponseStatus.BAD_REQUEST,
          ("Invalid request uri: " + uri).getBytes(),
          false,
          ctx);
      return;
    }

    /**
     *  Skip ACL for requests to /metadata, /admin and /health as there's no sensitive information in the response.
     */
    Set<QueryAction> queriesToSkipAcl =
        new HashSet<>(Arrays.asList(QueryAction.METADATA, QueryAction.ADMIN, QueryAction.HEALTH));
    try {
      QueryAction queryAction = QueryAction.valueOf(requestParts[1].toUpperCase());
      if (queriesToSkipAcl.contains(queryAction)) {
        ReferenceCountUtil.retain(req);
        ctx.fireChannelRead(req);
        return;
      }
    } catch (IllegalArgumentException illegalArgumentException) {
      throw new VeniceException("Unknown query action: " + requestParts[1]);
    }

    String storeName = extractStoreName(requestParts[2]);

    String method = req.method().name();
    try {
      Store store = metadataRepository.getStoreOrThrow(storeName);
      if (store.isSystemStore()) {
        // Ignore ACL for Venice system stores. System stores should be world readable and only contain public
        // information.
        ReferenceCountUtil.retain(req);
        ctx.fireChannelRead(req);
      } else {
        try {
          /**
           * TODO: Consider making this the first check, so that we optimize for the hot path. If rejected, then we
           *       could check whether the request is for a system store, METADATA, etc.
           */
          if (accessController.hasAccess(clientCert, storeName, method)) {
            // Client has permission. Proceed
            ReferenceCountUtil.retain(req);
            ctx.fireChannelRead(req);
          } else {
            // Fact:
            // Request gets rejected.
            // Possible Reasons:
            // A. ACL not found. OR,
            // B. ACL exists but caller does not have permission.

            String client = ctx.channel().remoteAddress().toString(); // ip and port
            String errLine = String.format("%s requested %s %s", client, method, req.uri());

            if (!accessController.isFailOpen() && !accessController.hasAcl(storeName)) { // short circuit, order matters
              // Case A
              // Conditions:
              // 0. (outside) Store exists and is being access controlled. AND,
              // 1. (left) The following policy is applied: if ACL not found, reject the request. AND,
              // 2. (right) ACL not found.
              // Result:
              // Request is rejected by DynamicAccessController#hasAccess()
              // Root cause:
              // Requested resource exists but does not have ACL.
              // Action:
              // return 401 Unauthorized
              LOGGER.warn("Requested store does not have ACL: {}", errLine);
              LOGGER.debug(
                  "Existing stores: {}",
                  () -> metadataRepository.getAllStores()
                      .stream()
                      .map(Store::getName)
                      .sorted()
                      .collect(Collectors.toList()));
              LOGGER.debug(
                  "Access-controlled stores: {}",
                  () -> accessController.getAccessControlledResources().stream().sorted().collect(Collectors.toList()));
              NettyUtils.setupResponseAndFlush(
                  HttpResponseStatus.UNAUTHORIZED,
                  ("ACL not found!\n" + "Either it has not been created, or can not be loaded.\n"
                      + "Please create the ACL, or report the error if you know for sure that ACL exists for this store: "
                      + storeName).getBytes(),
                  false,
                  ctx);
            } else {
              // Case B
              // Conditions:
              // 1. Fail closed, and ACL found. OR,
              // 2. Fail open, and ACL found. OR,
              // 3. Fail open, and ACL not found.
              // Analyses:
              // (1) ACL exists, therefore result is determined by ACL.
              // Since the request has been rejected, it must be due to lack of permission.
              // (2) ACL exists, therefore result is determined by ACL.
              // Since the request has been rejected, it must be due to lack of permission.
              // (3) In such case, request would NOT be rejected in the first place,
              // according to the definition of hasAccess() in DynamicAccessController interface.
              // Contradiction to the fact, therefore this case is impossible.
              // Root cause:
              // Caller does not have permission to access the resource.
              // Action:
              // return 403 Forbidden
              LOGGER.debug("Unauthorized access rejected: {}", errLine);
              NettyUtils.setupResponseAndFlush(
                  HttpResponseStatus.FORBIDDEN,
                  ("Access denied!\n"
                      + "If you are the store owner, add this application (or your own username for Venice shell client) to the store ACL.\n"
                      + "Otherwise, ask the store owner for read permission.").getBytes(),
                  false,
                  ctx);
            }
          }
        } catch (AclException e) {
          String client = ctx.channel().remoteAddress().toString(); // ip and port
          String errLine = String.format("%s requested %s %s", client, method, req.uri());

          if (accessController.isFailOpen()) {
            LOGGER.warn("Exception occurred! Access granted: {} {}", errLine, e);
            ReferenceCountUtil.retain(req);
            ctx.fireChannelRead(req);
          } else {
            LOGGER.warn("Exception occurred! Access rejected: {} {}", errLine, e);
            NettyUtils.setupResponseAndFlush(HttpResponseStatus.FORBIDDEN, new byte[0], false, ctx);
          }
        }
      }
    } catch (VeniceNoStoreException noStoreException) {
      String client = ctx.channel().remoteAddress().toString(); // ip and port
      LOGGER.debug("Requested store does not exist: {} requested {} {}", client, method, req.uri());
      NettyUtils.setupResponseAndFlush(
          HttpResponseStatus.BAD_REQUEST,
          ("Invalid Venice store name: " + storeName).getBytes(),
          false,
          ctx);
    }
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call,
      Metadata headers,
      ServerCallHandler<ReqT, RespT> next) {
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(next.startCall(call, headers)) {
      @Override
      public void onMessage(ReqT message) {
        X509Certificate clientCert;
        try {
          clientCert = extractClientCert(call);
        } catch (SSLPeerUnverifiedException e) {
          throw new VeniceException(e);
        }
        VeniceClientRequest request = (VeniceClientRequest) message;

        String storeName = extractStoreName(request.getResourceName());
        String method = request.getMethod();

        if (storeName.equals("") || method.equals("")) {
          call.close(Status.INVALID_ARGUMENT.withDescription("Invalid request"), new Metadata());
        }

        Store store;
        try {
          store = metadataRepository.getStoreOrThrow(storeName);
        } catch (VeniceNoStoreException noStoreException) {
          String client = Objects.requireNonNull(call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)).toString();
          LOGGER.debug("Requested store does not exist: {} requested {} {}", client, method, storeName);
          call.close(
              Status.INVALID_ARGUMENT.withDescription("Invalid Venice store name: " + storeName),
              new Metadata());
          return;
        }

        if (store.isSystemStore()) {
          super.onMessage(message);
          return;
        }

        try {
          if (accessController.hasAccess(clientCert, storeName, method)) {
            super.onMessage(message);
          } else {
            String client =
                Objects.requireNonNull(call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)).toString();
            String errLine = String.format("%s requested %s %s", client, method, storeName);

            if (!accessController.isFailOpen() && !accessController.hasAcl(storeName)) {
              LOGGER.warn("Requested store does not have ACL: {}", errLine);
              LOGGER.debug(
                  "Existing stores: {}",
                  () -> metadataRepository.getAllStores()
                      .stream()
                      .map(Store::getName)
                      .sorted()
                      .collect(Collectors.toList()));
              LOGGER.debug(
                  "Access-controlled stores: {}",
                  () -> accessController.getAccessControlledResources().stream().sorted().collect(Collectors.toList()));
              String responseMessage = "ACL not found!\n" + "Either it has not been created, or can not be loaded.\n"
                  + "Please create the ACL, or report the error if you know for sure that ACL exists for this store: "
                  + storeName;
              call.close(Status.PERMISSION_DENIED.withDescription(responseMessage), new Metadata());
            } else {
              String responseMessage = "Access denied!\n"
                  + "If you are the store owner, add this application (or your own username for Venice shell client) to the store ACL.\n"
                  + "Otherwise, ask the store owner for read permission.";
              call.close(Status.PERMISSION_DENIED.withDescription(responseMessage), new Metadata());
            }
            return;
          }
        } catch (AclException e) {
          String client = Objects.requireNonNull(call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)).toString();
          String errLine = String.format("%s requested %s %s", client, method, storeName);

          if (accessController.isFailOpen()) {
            LOGGER.warn("Exception occurred! Access granted: {} {}", errLine, e);
            super.onMessage(message);
          } else {
            LOGGER.warn("Exception occurred! Access rejected: {} {}", errLine, e);
            call.close(Status.PERMISSION_DENIED.withDescription("Access denied"), new Metadata());
          }
          return;
        }

        super.onMessage(message);
      }
    };
  }
}
