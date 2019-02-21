package com.github.qcloudsms.async;

import com.github.qcloudsms.httpclient.AsyncHTTPClient;
import com.github.qcloudsms.httpclient.HTTPRequest;
import com.github.qcloudsms.httpclient.ResponseHandler;

import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.netty.util.AttributeKey;
import io.netty.channel.ChannelHandlerContext;


public class NettyHTTPClient extends AsyncHTTPClient {

    private boolean inited;
    private int maxConnPoolSize;

    private EventLoopGroup group;
    private SslContext sslCtx;
    private Bootstrap bootstrap;
    private AbstractChannelPoolMap<InetSocketAddress, FixedChannelPool> pools;

    private final AttributeKey<RequestInfo> attrKey = AttributeKey.valueOf("RequestInfo");

    public NettyHTTPClient() {
        this(2048);
    }

    public NettyHTTPClient(int maxConnPoolSize) {
        this.maxConnPoolSize = maxConnPoolSize;
        inited = false;
        group = null;
        sslCtx = null;
        bootstrap = null;
        pools = null;
    }

    public void init() throws IOException {
        sslCtx = SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE).build();

        group = new NioEventLoopGroup();

        bootstrap = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.TCP_NODELAY, true);

        pools = new AbstractChannelPoolMap<InetSocketAddress, FixedChannelPool>() {
            @Override
            protected FixedChannelPool newPool(InetSocketAddress addr) {
                return new FixedChannelPool(bootstrap.remoteAddress(addr), new ChannelPoolHandler() {
                    public void channelCreated(Channel channel) throws Exception {
                        channel.pipeline()
                            .addLast(sslCtx.newHandler(channel.alloc()))
                            .addLast(new HttpClientCodec())
                            .addLast(new HttpContentDecompressor())
                            .addLast(new HttpObjectAggregator(1024 * 1024))
                            .addLast("IN", new NettyHTTPClientInboundHandler(attrKey))
                            .addLast("OUT", new NettyHTTPClientOutboundHandler(attrKey));
                    }

                    public void channelReleased(Channel channel) throws Exception {
                        // pass
                    }

                    public void channelAcquired(Channel channel) throws Exception {
                        // pass
                    }

                }, maxConnPoolSize);
            }
        };

        inited = true;
    }

    public void close() {
        group.shutdownGracefully();
        try {
            group.awaitTermination(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            group.shutdownGracefully(0, 20, TimeUnit.SECONDS);
        }

        if (!group.isTerminated()) {
            group.shutdownNow();
        }

        // pools is AutoCloseable
    }

    public int submit(final HTTPRequest request, final ResponseHandler handler) {
        assert inited;

        final URI uri = URI.create(request.url);
        String scheme = uri.getScheme() == "http" ? "http" : "https";
        int port = uri.getPort();
        if (port == -1) {
            if ("https".equalsIgnoreCase(scheme)) {
                port = 443;
            } else {
                port = 80;
            }
        }

        InetSocketAddress addr;
        addr = InetSocketAddress.createUnresolved(uri.getHost(), port);

        final FixedChannelPool pool = pools.get(addr);
        final Future<Channel> future = pool.acquire();
        final RequestInfo info = new RequestInfo(request, handler);

        future.addListener(new FutureListener<Channel>() {
            @Override
            public void operationComplete(Future<Channel> f) {
                if (f.isSuccess()) {
                    final Channel channel = future.getNow();

                    // Create request info
                    ChannelHandlerContext ctx = channel.pipeline().context("OUT");
                    ctx.attr(attrKey).set(info);

                    // Write and flush to remote
                    channel.writeAndFlush(toNettyHttpRequest(request, uri));

                    // Relase channel to pool
                    pool.release(channel);
                } else {
                    // Call error handler
                    handler.onError(f.cause(), null);
                }
            }
        });

        return 0;
    }

    protected DefaultFullHttpRequest toNettyHttpRequest(HTTPRequest request, URI uri) {
        StringBuilder path = new StringBuilder(uri.getRawPath());
        path.append("?");
        Iterator<Map.Entry<String, String>> it = request.parameters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> param = it.next();
            path.append(param.getKey());
            path.append("=");
            path.append(param.getValue());
            if (it.hasNext()) {
                path.append("&");
            }
        }

        // Transfrom to netty http request
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
            HttpMethod.valueOf(request.method.name()), path.toString());

        for (Map.Entry<String, String> entry: request.headers.entrySet()) {
            req.headers().add(entry.getKey(), entry.getValue());
        }
        req.headers().set(HttpHeaderNames.HOST, uri.getHost());
        req.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        req.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);

        ByteBuf buf = Unpooled.copiedBuffer(request.body.getBytes(request.bodyCharset));
        req.headers().set(HttpHeaders.Names.CONTENT_LENGTH, buf.readableBytes());
        req.content().writeBytes(buf);

        return req;
    }
}
