package io.joyrpc.invoker;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import io.joyrpc.Invoker;
import io.joyrpc.InvokerAware;
import io.joyrpc.Result;
import io.joyrpc.cluster.discovery.config.Configure;
import io.joyrpc.config.InterfaceOption;
import io.joyrpc.context.injection.Transmit;
import io.joyrpc.exception.RpcException;
import io.joyrpc.extension.URL;
import io.joyrpc.protocol.message.Invocation;
import io.joyrpc.protocol.message.RequestMessage;
import io.joyrpc.util.Futures;
import io.joyrpc.util.Shutdown;
import io.joyrpc.util.Status;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

import static io.joyrpc.Plugin.TRANSMIT;
import static io.joyrpc.util.Status.*;

/**
 * 抽象服务调用
 */
public abstract class AbstractService implements Invoker {
    protected static final AtomicReferenceFieldUpdater<AbstractService, Status> STATE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(AbstractService.class, Status.class, "status");
    /**
     * 代理的接口类
     */
    protected Class<?> interfaceClass;
    /**
     * 接口真实名称
     */
    protected String interfaceName;
    /**
     * URL
     */
    protected URL url;
    /**
     * 名称
     */
    protected String name;
    /**
     * 别名
     */
    protected String alias;
    /**
     * 是否是系统服务
     */
    protected boolean system;
    /**
     * 配置器
     */
    protected Configure configure;
    /***
     * 订阅的URL
     */
    protected URL subscribeUrl;
    /**
     * 方法选项
     */
    protected InterfaceOption option;
    /**
     * 调用链
     */
    protected Invoker chain;
    /**
     * 透传插件
     */
    protected Iterable<Transmit> transmits = TRANSMIT.extensions();
    /**
     * 调用计数器
     */
    protected AtomicLong requests = new AtomicLong(0);
    /**
     * 打开的结果
     */
    protected volatile CompletableFuture<Void> openFuture;
    /**
     * 关闭Future
     */
    protected volatile CompletableFuture<Void> closeFuture;
    /**
     * 等到请求处理完
     */
    protected volatile CompletableFuture<Void> flyingFuture;
    /**
     * 状态
     */
    protected volatile Status status = CLOSED;
    /**
     * 构建器
     */
    protected Consumer<InvokerAware> builder = this::setup;

    public URL getUrl() {
        return url;
    }

    @Override
    public String getName() {
        return name;
    }

    public Class<?> getInterfaceClass() {
        return interfaceClass;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public String getAlias() {
        return alias;
    }

    public InterfaceOption getOption() {
        return option;
    }

    /**
     * 关闭异常
     *
     * @return 异常
     */
    protected abstract Throwable shutdownException();

    @Override
    public CompletableFuture<Result> invoke(final RequestMessage<Invocation> request) {
        CompletableFuture<Result> future;
        //判断状态
        if ((Shutdown.isShutdown() || status != Status.OPENED) && !system) {
            //系统状服务允许执行，例如注册中心在关闭的时候进行注销操作
            if (request.getOption() == null) {
                try {
                    setup(request);
                } catch (Throwable ignored) {
                }
            }
            Result result = new Result(request.getContext(), shutdownException());
            onComplete(request, result);
            return CompletableFuture.completedFuture(result);
        }
        //增加计数器
        requests.incrementAndGet();
        try {
            if (request.getOption() == null) {
                setup(request);
            }
            future = doInvoke(request);
        } catch (Throwable e) {
            //如果抛出了异常
            if (e instanceof RpcException) {
                future = Futures.completeExceptionally(e);
            } else {
                future = Futures.completeExceptionally(new RpcException("Error occurs while invoking, caused by " + e.getMessage(), e));
            }
        }
        future.whenComplete((result, throwable) -> {
            if (requests.decrementAndGet() == 0 && flyingFuture != null) {
                //通知请求已经完成，触发优雅关闭
                flyingFuture.complete(null);
            }
            //触发结束操作，可以进行上下文额外处理，例如事务处理
            onComplete(request, throwable != null ? new Result(request.getContext(), throwable) : result);
        });
        return future;
    }

    /**
     * 执行调用，可以用于跟踪拦截
     *
     * @param request 请求
     * @return CompletableFuture
     */
    protected CompletableFuture<Result> doInvoke(final RequestMessage<Invocation> request) {
        //执行调用链
        return chain.invoke(request);
    }

    /**
     * 结束
     *
     * @param request 请求
     * @param result  结果
     */
    protected void onComplete(RequestMessage<Invocation> request, Result result) {

    }

    /**
     * 异步打开
     *
     * @return CompletableFuture
     */
    public CompletableFuture<Void> open() {
        if (STATE_UPDATER.compareAndSet(this, CLOSED, OPENING)) {
            final CompletableFuture<Void> future = new CompletableFuture<>();
            openFuture = future;
            closeFuture = null;
            flyingFuture = null;
            doOpen().whenComplete((v, t) -> {
                if (openFuture != future || t == null && !STATE_UPDATER.compareAndSet(this, OPENING, OPENED)) {
                    future.completeExceptionally(new IllegalStateException("state is illegal."));
                } else if (t != null) {
                    //出现了异常
                    future.completeExceptionally(t);
                    //自动关闭
                    close();
                } else {
                    future.complete(null);
                }
            });
            return future;
        } else {
            switch (status) {
                case OPENING:
                case OPENED:
                    //可重入，没有并发调用
                    return openFuture;
                default:
                    //其它状态不应该并发执行
                    return Futures.completeExceptionally(new IllegalStateException("state is illegal."));
            }
        }
    }

    @Override
    public CompletableFuture<Void> close(final boolean gracefully) {
        if (STATE_UPDATER.compareAndSet(this, OPENING, CLOSING)) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            //请求数肯定为0
            closeFuture = future;
            openFuture.whenComplete((v, t) -> {
                status = CLOSED;
                future.complete(null);
            });
            return future;
        } else if (STATE_UPDATER.compareAndSet(this, OPENED, CLOSING)) {
            //状态从打开到关闭中，该状态只能变更为CLOSED
            CompletableFuture<Void> future = new CompletableFuture<>();
            closeFuture = future;
            flyingFuture = new CompletableFuture<>();
            flyingFuture.whenComplete((v, t) -> doClose().whenComplete((o, s) -> {
                status = CLOSED;
                future.complete(null);
            }));
            //判断是否请求已经完成
            if (!gracefully || requests.get() == 0) {
                flyingFuture.complete(null);
            }
            return future;
        } else {
            switch (status) {
                case CLOSING:
                case CLOSED:
                    return closeFuture;
                default:
                    return Futures.completeExceptionally(new IllegalStateException("state is illegal."));
            }
        }

    }

    /**
     * 打开
     *
     * @return CompletableFuture
     */
    protected abstract CompletableFuture<Void> doOpen();

    /**
     * 关闭
     *
     * @return CompletableFuture
     */
    protected abstract CompletableFuture<Void> doClose();

    /**
     * 设置参数
     *
     * @param target 目标对象
     */
    protected void setup(final InvokerAware target) {
        target.setClassName(interfaceName);
        target.setClass(interfaceClass);
        target.setUrl(url);
        target.setup();
    }

    public Consumer<InvokerAware> getBuilder() {
        return builder;
    }
}
