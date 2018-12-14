/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.pool;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.http.annotation.Contract;
import org.apache.http.annotation.ThreadingBehavior;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;

/**
 * Abstract synchronous (blocking) pool of connections.
 * <p>
 * Please note that this class does not maintain its own pool of execution {@link Thread}s.
 * Therefore, one <b>must</b> call {@link Future#get()} or {@link Future#get(long, TimeUnit)}
 * method on the {@link Future} object returned by the
 * {@link #lease(Object, Object, FutureCallback)} method in order for the lease operation
 * to complete.
 *
 * @param <T> the route type that represents the opposite endpoint of a pooled
 *   connection.
 * @param <C> the connection type.
 * @param <E> the type of the pool entry containing a pooled connection.
 * @since 4.2
 */

/**
 * 抽象同步（阻塞）连接池
 *
 * 注意：此类并不维护它自己的执行线程池。
 * 因此，必须等待lease操作完成，直到lease(Object, Object, FutureCallback)返回future对象,在future对象上调用 Future#get()或者Future#get(long, TimeUnit)方法。
 *
 * <T> 代表池化连接对应endpoint的路由类型
 *
 * <C> 连接类型
 *
 *  <E> pool entry 包含的池化连接的类型
 */
@Contract(threading = ThreadingBehavior.SAFE_CONDITIONAL)
public abstract class AbstractConnPool<T, C, E extends PoolEntry<T, C>>
                                               implements ConnPool<T, E>, ConnPoolControl<T> {

    private final Lock lock;
    private final Condition condition;
    private final ConnFactory<T, C> connFactory;
    private final Map<T, RouteSpecificPool<T, C, E>> routeToPool;
    private final Set<E> leased;
    private final LinkedList<E> available;
    private final LinkedList<Future<E>> pending;
    private final Map<T, Integer> maxPerRoute;

    private volatile boolean isShutDown;
    private volatile int defaultMaxPerRoute;
    private volatile int maxTotal;
    private volatile int validateAfterInactivity;

    public AbstractConnPool(
            final ConnFactory<T, C> connFactory,
            final int defaultMaxPerRoute,
            final int maxTotal) {
        super();
        this.connFactory = Args.notNull(connFactory, "Connection factory");
        this.defaultMaxPerRoute = Args.positive(defaultMaxPerRoute, "Max per route value");
        this.maxTotal = Args.positive(maxTotal, "Max total value");
        this.lock = new ReentrantLock();
        this.condition = this.lock.newCondition();
        this.routeToPool = new HashMap<T, RouteSpecificPool<T, C, E>>();
        this.leased = new HashSet<E>();
        this.available = new LinkedList<E>();
        this.pending = new LinkedList<Future<E>>();
        this.maxPerRoute = new HashMap<T, Integer>();
    }

    /**
     * Creates a new entry for the given connection with the given route.
     */
    protected abstract E createEntry(T route, C conn);

    /**
     * @since 4.3
     */
    protected void onLease(final E entry) {
    }

    /**
     * @since 4.3
     */
    protected void onRelease(final E entry) {
    }

    /**
     * @since 4.4
     */
    protected void onReuse(final E entry) {
    }

    /**
     * @since 4.4
     */
    protected boolean validate(final E entry) {
        return true;
    }

    public boolean isShutdown() {
        return this.isShutDown;
    }

    /**
     * Shuts down the pool.
     */
    public void shutdown() throws IOException {
        if (this.isShutDown) {
            return ;
        }
        this.isShutDown = true;
        this.lock.lock();
        try {
            for (final E entry: this.available) {
                entry.close();
            }
            for (final E entry: this.leased) {
                entry.close();
            }
            for (final RouteSpecificPool<T, C, E> pool: this.routeToPool.values()) {
                pool.shutdown();
            }
            this.routeToPool.clear();
            this.leased.clear();
            this.available.clear();
        } finally {
            this.lock.unlock();
        }
    }

    private RouteSpecificPool<T, C, E> getPool(final T route) {
        RouteSpecificPool<T, C, E> pool = this.routeToPool.get(route);
        if (pool == null) {
            pool = new RouteSpecificPool<T, C, E>(route) {

                @Override
                protected E createEntry(final C conn) {
                    return AbstractConnPool.this.createEntry(route, conn);
                }

            };
            this.routeToPool.put(route, pool);
        }
        return pool;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Please note that this class does not maintain its own pool of execution
     * {@link Thread}s. Therefore, one <b>must</b> call {@link Future#get()}
     * or {@link Future#get(long, TimeUnit)} method on the {@link Future}
     * returned by this method in order for the lease operation to complete.
     */
    @Override
    public Future<E> lease(final T route, final Object state, final FutureCallback<E> callback) {
        Args.notNull(route, "Route");
        Asserts.check(!this.isShutDown, "Connection pool shut down");

        return new Future<E>() {

            private final AtomicBoolean cancelled = new AtomicBoolean(false);
            private final AtomicBoolean done = new AtomicBoolean(false);
            private final AtomicReference<E> entryRef = new AtomicReference<E>(null);

            @Override
            public boolean cancel(final boolean mayInterruptIfRunning) {
                if (cancelled.compareAndSet(false, true)) {
                    done.set(true);
                    lock.lock();
                    try {
                        condition.signalAll();
                    } finally {
                        lock.unlock();
                    }
                    if (callback != null) {
                        callback.cancelled();
                    }
                    return true;
                } else {
                    return false;
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public boolean isDone() {
                return done.get();
            }

            @Override
            public E get() throws InterruptedException, ExecutionException {
                try {
                    return get(0L, TimeUnit.MILLISECONDS);
                } catch (final TimeoutException ex) {
                    throw new ExecutionException(ex);
                }
            }

            @Override
            public E get(final long timeout, final TimeUnit tunit) throws InterruptedException, ExecutionException, TimeoutException {
                final E entry = entryRef.get();
                if (entry != null) {
                    return entry;
                }
                synchronized (this) {
                    try {
                        for (;;) {
                            //尝试根据已知的route和state从池中获取（租借）一个连接
                            final E leasedEntry = getPoolEntryBlocking(route, state, timeout, tunit, this);
                            if (validateAfterInactivity > 0)  {
                                if (leasedEntry.getUpdated() + validateAfterInactivity <= System.currentTimeMillis()) {
                                    if (!validate(leasedEntry)) {
                                        leasedEntry.close();
                                        release(leasedEntry, false);
                                        continue;
                                    }
                                }
                            }
                            entryRef.set(leasedEntry);
                            done.set(true);
                            onLease(leasedEntry);
                            if (callback != null) {
                                callback.completed(leasedEntry);
                            }
                            return leasedEntry;
                        }
                    } catch (final IOException ex) {
                        done.set(true);
                        if (callback != null) {
                            callback.failed(ex);
                        }
                        throw new ExecutionException(ex);
                    }
                }
            }

        };
    }

    /**
     * Attempts to lease a connection for the given route and with the given
     * state from the pool.
     * <p>
     * Please note that this class does not maintain its own pool of execution
     * {@link Thread}s. Therefore, one <b>must</b> call {@link Future#get()}
     * or {@link Future#get(long, TimeUnit)} method on the {@link Future}
     * returned by this method in order for the lease operation to complete.
     *
     * @param route route of the connection.
     * @param state arbitrary object that represents a particular state
     *  (usually a security principal or a unique token identifying
     *  the user whose credentials have been used while establishing the connection).
     *  May be {@code null}.
     * @return future for a leased pool entry.
     */
    public Future<E> lease(final T route, final Object state) {
        return lease(route, state, null);
    }

    private E getPoolEntryBlocking(
            final T route, final Object state,
            final long timeout, final TimeUnit tunit,
            final Future<E> future) throws IOException, InterruptedException, TimeoutException {

        Date deadline = null;
        if (timeout > 0) {
            deadline = new Date (System.currentTimeMillis() + tunit.toMillis(timeout));
        }
        //加锁（可重入）
        this.lock.lock();
        try {
            //获取到RouteSpecificPool，注意此处获取到的不是直接是connection
            final RouteSpecificPool<T, C, E> pool = getPool(route);
            E entry;
            for (;;) {
                Asserts.check(!this.isShutDown, "Connection pool shut down");
                for (;;) {
                    //从RouteSpecificPool中获取PoolEntry
                    entry = pool.getFree(state);
                    //null则跳出循环
                    if (entry == null) {
                        break;
                    }
                    if (entry.isExpired(System.currentTimeMillis())) {
                        entry.close();
                    }
                    if (entry.isClosed()) {
                        this.available.remove(entry);
                        pool.free(entry, false);
                    } else {
                        //还没有关闭则跳出循环
                        break;
                    }
                }
                if (entry != null) {
                    this.available.remove(entry);
                    this.leased.add(entry);
                    onReuse(entry);
                    //获取到返回
                    return entry;
                }

                // New connection is needed
                //此时需要创建一个新连接
                final int maxPerRoute = getMax(route);
                // Shrink the pool prior to allocating a new connection
                //为了分配新连接收缩之前的池，注意：此处使用LRU算法
                final int excess = Math.max(0, pool.getAllocatedCount() + 1 - maxPerRoute);
                if (excess > 0) {
                    for (int i = 0; i < excess; i++) {
                        final E lastUsed = pool.getLastUsed();
                        if (lastUsed == null) {
                            break;
                        }
                        lastUsed.close();
                        this.available.remove(lastUsed);
                        pool.remove(lastUsed);
                    }
                }
                //如果RouteSpecificPool已分配的量小于每个路由的池的最大值
                if (pool.getAllocatedCount() < maxPerRoute) {
                    final int totalUsed = this.leased.size();
                    final int freeCapacity = Math.max(this.maxTotal - totalUsed, 0);
                    //如果还有空闲容量
                    if (freeCapacity > 0) {
                        final int totalAvailable = this.available.size();
                        //如果有效数量大于等于空闲量
                        if (totalAvailable > freeCapacity - 1) {
                            //如果有效量不为空，LRU
                            if (!this.available.isEmpty()) {
                                final E lastUsed = this.available.removeLast();
                                lastUsed.close();
                                final RouteSpecificPool<T, C, E> otherpool = getPool(lastUsed.getRoute());
                                otherpool.remove(lastUsed);
                            }
                        }
                        //创建一个新连接
                        final C conn = this.connFactory.create(route);
                        //放入RouteSpecificPool
                        entry = pool.add(conn);
                        //RouteSpecificPool放入池返回
                        this.leased.add(entry);
                        return entry;
                    }
                }
                //执行至此代表获取失败
                boolean success = false;
                try {
                    if (future.isCancelled()) {
                        throw new InterruptedException("Operation interrupted");
                    }
                    //放入RouteSpecificPool的链表中排队
                    pool.queue(future);
                    //放入本池的链表中排队
                    this.pending.add(future);
                    if (deadline != null) {
                        //在指定时间内等待Condition的通知
                        success = this.condition.awaitUntil(deadline);
                    } else {
                        //一直等待Condition的通知
                        this.condition.await();
                        success = true;
                    }
                    if (future.isCancelled()) {
                        throw new InterruptedException("Operation interrupted");
                    }
                } finally {
                    // In case of 'success', we were woken up by the
                    // connection pool and should now have a connection
                    // waiting for us, or else we're shutting down.
                    // Just continue in the loop, both cases are checked.
                    //两个链表中移除
                    pool.unqueue(future);
                    this.pending.remove(future);
                }
                // check for spurious wakeup vs. timeout
                //指定时间内没有等到成功通知，跳出
                if (!success && (deadline != null && deadline.getTime() <= System.currentTimeMillis())) {
                    break;
                }
            }
            throw new TimeoutException("Timeout waiting for connection");
        } finally {
            //最终解锁，必定解锁
            this.lock.unlock();
        }
    }

    @Override
    public void release(final E entry, final boolean reusable) {
        this.lock.lock();
        try {
            if (this.leased.remove(entry)) {
                final RouteSpecificPool<T, C, E> pool = getPool(entry.getRoute());
                pool.free(entry, reusable);
                if (reusable && !this.isShutDown) {
                    this.available.addFirst(entry);
                } else {
                    entry.close();
                }
                onRelease(entry);
                Future<E> future = pool.nextPending();
                if (future != null) {
                    this.pending.remove(future);
                } else {
                    future = this.pending.poll();
                }
                if (future != null) {
                    this.condition.signalAll();
                }
            }
        } finally {
            this.lock.unlock();
        }
    }

    private int getMax(final T route) {
        final Integer v = this.maxPerRoute.get(route);
        if (v != null) {
            return v.intValue();
        } else {
            return this.defaultMaxPerRoute;
        }
    }

    @Override
    public void setMaxTotal(final int max) {
        Args.positive(max, "Max value");
        this.lock.lock();
        try {
            this.maxTotal = max;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int getMaxTotal() {
        this.lock.lock();
        try {
            return this.maxTotal;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void setDefaultMaxPerRoute(final int max) {
        Args.positive(max, "Max per route value");
        this.lock.lock();
        try {
            this.defaultMaxPerRoute = max;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int getDefaultMaxPerRoute() {
        this.lock.lock();
        try {
            return this.defaultMaxPerRoute;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void setMaxPerRoute(final T route, final int max) {
        Args.notNull(route, "Route");
        this.lock.lock();
        try {
            if (max > -1) {
                this.maxPerRoute.put(route, Integer.valueOf(max));
            } else {
                this.maxPerRoute.remove(route);
            }
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int getMaxPerRoute(final T route) {
        Args.notNull(route, "Route");
        this.lock.lock();
        try {
            return getMax(route);
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public PoolStats getTotalStats() {
        this.lock.lock();
        try {
            return new PoolStats(
                    this.leased.size(),
                    this.pending.size(),
                    this.available.size(),
                    this.maxTotal);
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public PoolStats getStats(final T route) {
        Args.notNull(route, "Route");
        this.lock.lock();
        try {
            final RouteSpecificPool<T, C, E> pool = getPool(route);
            return new PoolStats(
                    pool.getLeasedCount(),
                    pool.getPendingCount(),
                    pool.getAvailableCount(),
                    getMax(route));
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Returns snapshot of all knows routes
     * @return the set of routes
     *
     * @since 4.4
     */
    public Set<T> getRoutes() {
        this.lock.lock();
        try {
            return new HashSet<T>(routeToPool.keySet());
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Enumerates all available connections.
     *
     * @since 4.3
     */
    protected void enumAvailable(final PoolEntryCallback<T, C> callback) {
        this.lock.lock();
        try {
            final Iterator<E> it = this.available.iterator();
            while (it.hasNext()) {
                final E entry = it.next();
                callback.process(entry);
                if (entry.isClosed()) {
                    final RouteSpecificPool<T, C, E> pool = getPool(entry.getRoute());
                    pool.remove(entry);
                    it.remove();
                }
            }
            purgePoolMap();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Enumerates all leased connections.
     *
     * @since 4.3
     */
    protected void enumLeased(final PoolEntryCallback<T, C> callback) {
        this.lock.lock();
        try {
            final Iterator<E> it = this.leased.iterator();
            while (it.hasNext()) {
                final E entry = it.next();
                callback.process(entry);
            }
        } finally {
            this.lock.unlock();
        }
    }

    private void purgePoolMap() {
        final Iterator<Map.Entry<T, RouteSpecificPool<T, C, E>>> it = this.routeToPool.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<T, RouteSpecificPool<T, C, E>> entry = it.next();
            final RouteSpecificPool<T, C, E> pool = entry.getValue();
            if (pool.getPendingCount() + pool.getAllocatedCount() == 0) {
                it.remove();
            }
        }
    }

    /**
     * Closes connections that have been idle longer than the given period
     * of time and evicts them from the pool.
     *
     * @param idletime maximum idle time.
     * @param tunit time unit.
     */
    public void closeIdle(final long idletime, final TimeUnit tunit) {
        Args.notNull(tunit, "Time unit");
        long time = tunit.toMillis(idletime);
        if (time < 0) {
            time = 0;
        }
        final long deadline = System.currentTimeMillis() - time;
        enumAvailable(new PoolEntryCallback<T, C>() {

            @Override
            public void process(final PoolEntry<T, C> entry) {
                if (entry.getUpdated() <= deadline) {
                    entry.close();
                }
            }

        });
    }

    /**
     * Closes expired connections and evicts them from the pool.
     */
    public void closeExpired() {
        final long now = System.currentTimeMillis();
        enumAvailable(new PoolEntryCallback<T, C>() {

            @Override
            public void process(final PoolEntry<T, C> entry) {
                if (entry.isExpired(now)) {
                    entry.close();
                }
            }

        });
    }

    /**
     * @return the number of milliseconds
     * @since 4.4
     */
    public int getValidateAfterInactivity() {
        return this.validateAfterInactivity;
    }

    /**
     * @param ms the number of milliseconds
     * @since 4.4
     */
    public void setValidateAfterInactivity(final int ms) {
        this.validateAfterInactivity = ms;
    }

    @Override
    public String toString() {
        final StringBuilder buffer = new StringBuilder();
        buffer.append("[leased: ");
        buffer.append(this.leased);
        buffer.append("][available: ");
        buffer.append(this.available);
        buffer.append("][pending: ");
        buffer.append(this.pending);
        buffer.append("]");
        return buffer.toString();
    }

}
