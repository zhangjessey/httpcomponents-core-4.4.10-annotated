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

package org.apache.http.protocol;

import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;

/**
 * HTTP protocol processor is a collection of protocol interceptors that
 * implements the 'Chain of Responsibility' pattern, where each individual
 * protocol interceptor is expected to work on a particular aspect of the HTTP
 * protocol the interceptor is responsible for.
 * <p>
 * Usually the order in which interceptors are executed should not matter as
 * long as they do not depend on a particular state of the execution context.
 * If protocol interceptors have interdependencies and therefore must be
 * executed in a particular order, they should be added to the protocol
 * processor in the same sequence as their expected execution order.
 * <p>
 * Protocol interceptors must be implemented as thread-safe. Similarly to
 * servlets, protocol interceptors should not use instance variables unless
 * access to those variables is synchronized.
 *
 * @since 4.0
 */

/**
 * HTTP协议处理器是一个协议拦截器的集合，实现了责任链模式，每个独立的协议拦截器都能够针对一个特殊的方面生效。
 *
 * 通常来说拦截器执行的顺序无所谓，只要它们不依赖 execution context的特殊状态。
 * 如果协议拦截器有相互依赖，必须以特殊的顺序执行，它们应该按照期待执行的顺序添加到协议处理器。
 *
 * 协议拦截器必须是线程安全的。就像是servlets一样，协议拦截器不应该使用实例变量，除非它们访问这些变量是同步的。
 */
public interface HttpProcessor
    extends HttpRequestInterceptor, HttpResponseInterceptor {

    // no additional methods
}
