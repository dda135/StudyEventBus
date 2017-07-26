/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

/**
 * Each event handler method has a thread mode, which determines in which thread the method is to be called by EventBus.
 * EventBus takes care of threading independently from the posting thread.
 * 
 * @see EventBus#register(Object)
 * @author Markus
 */
public enum ThreadMode {
    /**
     * 观察者的回调方法会在post的线程中进行回调。这个是默认值，可以避免线程的频繁切换
     * 相对适合一些简单的任务（可以知道在很短的时间内处理的）
     * 对于那些耗时的任务来说，如果当前post的线程为UI线程
     * 会导致posting这个过程的阻塞（UI线程不应该被阻塞），这样就不合适了
     */
    POSTING,

    /**
     * 观察者的回调方法在UI线程中进行回调。如果post在UI线程则直接调用，都这进入主线程Handler中处理。
     * 同样的，在这种模式下不应该进行耗时操作，从而避免阻塞UI线程
     */
    MAIN,

    /**
     * 观察者的回调方法在子线程中进行。如果当前post在子线程则直接调用。
     * 否则进入线程池中执行，在设计的时候是尽可能使用单一的线程
     * 也就是说在post连续多个事件的时候，要稍微留意一下阻塞的情况
     * 某一个事件回调导致子线程阻塞，这同样会阻塞后面的事件进行处理的时机
     */
    BACKGROUND,

    /**
     * 观察者的回调方法在子线程中进行。
     * 不同于BACKGROUND的是这里直接将回调在线程池中执行
     * 也就是调度的工作交给了线程池，默认使用的是cacheExecutor
     * 所以说响应速度还是很快的，但是也要注意不要导致CPU多余忙碌
     */
    ASYNC
}