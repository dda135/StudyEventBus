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

import android.os.Looper;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

/**
 * EventBus is a central publish/subscribe event system for Android. Events are posted ({@link #post(Object)}) to the
 * bus, which delivers it to subscribers that have a matching handler method for the event type. To receive events,
 * subscribers must register themselves to the bus using {@link #register(Object)}. Once registered, subscribers
 * receive events until {@link #unregister(Object)} is called. Event handling methods must be annotated by
 * {@link Subscribe}, must be public, return nothing (void), and have exactly one parameter
 * (the event).
 *
 * @author Markus Junginger, greenrobot
 */
public class EventBus {

    /** Log tag, apps may override it. */
    public static String TAG = "EventBus";
    //DCL单例模式
    static volatile EventBus defaultInstance;

    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();
    //原始事件和该事件可以进行回调的其他事件列表的集合
    //一般来说就是结合eventInheritance
    //然后会记录自己的所有父类和所实现的所有接口(包括每一个父类所实现的接口)等
    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<>();
    //回调的方法对应的参数(事件)和对应的订阅者单元(订阅的类和具体的某一个方法)所构成的集合
    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;
    //订阅者(类)和其所对应的所有事件的列表
    private final Map<Object, List<Class<?>>> typesBySubscriber;
    //黏性事件的对象所对应的类和对象所构成的集合
    private final Map<Class<?>, Object> stickyEvents;
    //每一个线程都有自己对应的发送状态等参数
    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };
    //用于在主线程进行事件的回调处理方法
    private final HandlerPoster mainThreadPoster;
    //用于在EventBus的线程池即executorService中的子线程进行事件的回调处理
    //内部的实现会在执行队列非空的情况下使用单一的线程一直执行，并且规定了最大等待(空闲)时间
    //只有在超过该空闲时间之后，再进行任务，才有可能使用其他线程
    //不过默认使用的是cacheExecutor，这意味着至少还有60s的空闲时间，在此期间也是能够继续复用线程的
    private final BackgroundPoster backgroundPoster;
    //用于在EventBus的线程池即executorService中的子线程进行事件的回调处理
    //区别于BackgroundPoster来说这个post是单纯通过线程池调度
    private final AsyncPoster asyncPoster;
    //用于查找所有观察者用于回调处理事件的方法
    private final SubscriberMethodFinder subscriberMethodFinder;
    //线程池，用于处理在子线程中进行的回调
    private final ExecutorService executorService;

    //当某一个事件在发送的时候有观察者处理，但是在进行函数回调的时候出现InvocationTargetException异常
    //此时是否抛出异常，如果抛出异常，那么sendSubscriberExceptionEvent和logSubscriberExceptions直接就无效了
    private final boolean throwSubscriberException;
    //当某一个事件在发送的时候有观察者处理，但是在进行函数回调的时候出现InvocationTargetException异常
    //此时是否要打印日志
    private final boolean logSubscriberExceptions;
    private final boolean logNoSubscriberMessages;
    //当某一个事件在发送的时候有观察者处理，但是在进行函数回调的时候出现InvocationTargetException异常
    //是否需要额外的发送一个预先定义的SubscriberExceptionEvent事件
    private final boolean sendSubscriberExceptionEvent;
    //当某一个事件在发送的时候没有观察者处理
    //是否需要额外的发送一个预先定义的NoSubscriberEvent事件，这个事件会记录的之前那个没有被处理的事件
    private final boolean sendNoSubscriberEvent;
    //是否处理子类事件，如果为true的时候，假设有一个事件A发送出去
    //而某一个观察者中的处理事件可能为A的父类，那么此时也会进行事件回调
    private final boolean eventInheritance;

    private final int indexCount;

    /** Convenience singleton for apps using a process-wide EventBus instance. */
    public static EventBus getDefault() {
        if (defaultInstance == null) {
            synchronized (EventBus.class) {
                if (defaultInstance == null) {
                    defaultInstance = new EventBus();
                }
            }
        }
        return defaultInstance;
    }

    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    /** For unit test primarily. */
    public static void clearCaches() {
        SubscriberMethodFinder.clearCaches();
        eventTypesCache.clear();
    }

    /**
     * Creates a new EventBus instance; each instance is a separate scope in which events are delivered. To use a
     * central bus, consider {@link #getDefault()}.
     */
    public EventBus() {
        this(DEFAULT_BUILDER);
    }

    EventBus(EventBusBuilder builder) {
        subscriptionsByEventType = new HashMap<>();
        typesBySubscriber = new HashMap<>();
        stickyEvents = new ConcurrentHashMap<>();
        mainThreadPoster = new HandlerPoster(this, Looper.getMainLooper(), 10);
        backgroundPoster = new BackgroundPoster(this);
        asyncPoster = new AsyncPoster(this);
        indexCount = builder.subscriberInfoIndexes != null ? builder.subscriberInfoIndexes.size() : 0;
        subscriberMethodFinder = new SubscriberMethodFinder(builder.subscriberInfoIndexes,
                builder.strictMethodVerification, builder.ignoreGeneratedIndex);
        logSubscriberExceptions = builder.logSubscriberExceptions;
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        throwSubscriberException = builder.throwSubscriberException;
        eventInheritance = builder.eventInheritance;
        executorService = builder.executorService;
    }

    /**
     * 注册一个观察者用于接收指定的一些事件，实际上就是查找回调方法，然后记录一堆数据
     * 当观察者不再关心这些事件的时候，要通过unregister注销，从而使得观察者不再接收那些事件的回调
     * 观察者必须要有回调方法，且该回调方法必须通过@Subscribe进行注解
     * 该注解还可以指定线程和优先级
     */
    public void register(Object subscriber) {
        //获得观察者的类，比方说在AFragment中注册，那么这里就会获得AFragment这个类
        Class<?> subscriberClass = subscriber.getClass();
        //通过观察者类查找里面定义的所有回调方法
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriberClass);
        synchronized (this) {//这个只是对象锁，这就意味着不同的EventBus实例，事件的回调不会互相上锁
            //为了防止处理过程中列表变化，这里必须上锁
            for (SubscriberMethod subscriberMethod : subscriberMethods) {
                subscribe(subscriber, subscriberMethod);
            }
        }
    }

    /**
     * 将当前观察者和对应的方法记录在缓存当中，相当于标记注册
     * 不允许重复添加！所以说重复添加之前必须进行反注册或者清除缓存
     * 如果当前观察者进行回调的方法处理黏性事件，那么需要尝试进行黏性事件回调
     * @param subscriber 当前的观察者
     * @param subscriberMethod 当前的观察者所定义的某一个具体的事件回调方法
     */
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod) {
        //获得当前方法对应的事件，实际上就是观察者所观察的某一个事件
        Class<?> eventType = subscriberMethod.eventType;
        //通过观察者和该方法定义一个个新的观察单元
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod);
        //从缓存中通过当前事件获得观察单元
        //同一个事件当然可以有不同的观察者
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions == null) {
            //当前事件没有观察单元处理，新建
            subscriptions = new CopyOnWriteArrayList<>();
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
            //其实就是不允许同一个类重复注册，要重新注册，那么之前必须反注册
            if (subscriptions.contains(newSubscription)) {
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event "
                        + eventType);
            }
        }
        //将当前观察元素添加到缓存中
        int size = subscriptions.size();
        for (int i = 0; i <= size; i++) {
            //可以看到，在注册的时候优先级大的会在同一个事件的观察元素队列中靠前
            if (i == size || subscriberMethod.priority > subscriptions.get(i).subscriberMethod.priority) {
                subscriptions.add(i, newSubscription);
                break;
            }
        }
        //从缓存中通过观察者获得其所观察的所有事件类型
        List<Class<?>> subscribedEvents = typesBySubscriber.get(subscriber);
        if (subscribedEvents == null) {//之前没有缓存，新建
            subscribedEvents = new ArrayList<>();
            typesBySubscriber.put(subscriber, subscribedEvents);
        }
        subscribedEvents.add(eventType);//将当前事件放入缓存，建立了观察者和其所观察的某一个事件的缓存

        if (subscriberMethod.sticky) {//当前观察者的回调方法是处理黏性事件的
            if (eventInheritance) {
                Set<Map.Entry<Class<?>, Object>> entries = stickyEvents.entrySet();
                for (Map.Entry<Class<?>, Object> entry : entries) {//遍历黏性事件缓存
                    Class<?> candidateEventType = entry.getKey();
                    if (eventType.isAssignableFrom(candidateEventType)) {
                        //如果当前观察者的回调方法处理的事件类型为缓存中某一个黏性事件的父类
                        //举例说明当前方法处理的黏性事件为A
                        //之前已经有黏性事件B发送过了，而且满足B extends A
                        //那么当前B事件也会进行回调处理
                        Object stickyEvent = entry.getValue();
                        checkPostStickyEventToSubscription(newSubscription, stickyEvent);
                    }
                }
            } else {
                //从缓存中通过当前事件来获取黏性事件，如果在这之前已经有黏性事件发送，那么缓存中必定有值
                Object stickyEvent = stickyEvents.get(eventType);
                //如果缓存中有值，即null != stickyEvent，说明之前该黏性事件已经发送过了，尝试进行回调
                checkPostStickyEventToSubscription(newSubscription, stickyEvent);
            }
        }
    }

    /**
     * 检查当前黏性事件是否为空
     * 非空则进行事件回调处理
     * @param newSubscription 订阅元素（当前的观察者和其处理当前黏性事件的方法）
     * @param stickyEvent 当前可能需要进行回调的黏性事件
     */
    private void checkPostStickyEventToSubscription(Subscription newSubscription, Object stickyEvent) {
        if (stickyEvent != null) {
            postToSubscription(newSubscription, stickyEvent, Looper.getMainLooper() == Looper.myLooper());
        }
    }

    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }

    /**
     * 反注册制定的观察者，会将当前观察者和其观察的事件缓存移除
     * 并且将对应的观察元素移除
     * @param subscriber 当前不再需要观察事件的观察者
     */
    public synchronized void unregister(Object subscriber) {
        //实际上就是将当前观察者已经注册的所有缓存移除
        //包括自身和事件的缓存、自身单独拥有的事件移除等等
        List<Class<?>> subscribedTypes = typesBySubscriber.get(subscriber);
        if (subscribedTypes != null) {
            for (Class<?> eventType : subscribedTypes) {
                unsubscribeByEventType(subscriber, eventType);
            }
            typesBySubscriber.remove(subscriber);
        } else {
            Log.w(TAG, "Subscriber to unregister was not registered before: " + subscriber.getClass());
        }
    }

    /**
     * 通过制定的观察者和事件，进行反注册，就是移除缓存
     * @param subscriber 当前需要反注册的观察者
     * @param eventType 当前需要反注册的观察者曾经观察的事件
     * */
    private void unsubscribeByEventType(Object subscriber, Class<?> eventType) {
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions != null) {
            int size = subscriptions.size();
            for (int i = 0; i < size; i++) {
                Subscription subscription = subscriptions.get(i);
                if (subscription.subscriber == subscriber) {
                    subscription.active = false;
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    /**
     * 发送一个事件到事件总线上，并且尝试进行对应的回调
     * @param event 当前需要发送的事件
     * */
    public void post(Object event) {
        //通过当前调用的线程获得对应的PostingThreadState
        PostingThreadState postingState = currentPostingThreadState.get();
        //获取当前线程的事件队列
        List<Object> eventQueue = postingState.eventQueue;
        eventQueue.add(event);

        if (!postingState.isPosting) {//当前线程未处于事件发送中，开始进行事件的发送
            //记录当前线程是否为主线程
            postingState.isMainThread = Looper.getMainLooper() == Looper.myLooper();
            //标记当前线程开始发送事件
            postingState.isPosting = true;
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                postSingleEvent(eventQueue.remove(0), postingState);
                while (!eventQueue.isEmpty()) {//会尝试将当前队列中的所有事件都发送出去
                }
            } finally {
                //事件发送完成
                postingState.isPosting = false;//当前未处于发送状态
                postingState.isMainThread = false;//每次发送的时候都会重置
            }
        }
    }

    /**
     * 用于在观察者的事件回调方法中调用，那么当前事件所对应的后续的观察元素的回调都不会进行
     * 常用的场景就是高优先级阻断低优先级的执行
     * 调用这个方法必须要求当前的回调方法是POSTING线程，因为这样才能保证同步性
     * 这个和postSingleEventForEventType处理的逻辑有关
     */
    public void cancelEventDelivery(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();
        //实际上这个方法是用于在事件回调的时候调用的，所以说需要检查当前是否处于事件发送中
        if (!postingState.isPosting) {
            throw new EventBusException(
                    "This method may only be called from inside event handling methods on the posting thread");
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
        } else if (postingState.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.POSTING) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        postingState.canceled = true;
    }

    /**
     * 发送一个黏性事件
     * 这个事件会保留在内存当中，然后如果发送之后还有黏性事件的观察者注册了
     * 此时该事件也可能会进行回调处理
     */
    public void postSticky(Object event) {
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);//黏性事件会保留在内存当中
        }
        //当前先发送事件给之前注册了当前事件的观察者
        post(event);
    }

    /**
     * Gets the most recent sticky event for the given type.
     *
     * @see #postSticky(Object)
     */
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.get(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type.
     *
     * @see #postSticky(Object)
     */
    public <T> T removeStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Removes the sticky event if it equals to the given event.
     *
     * @return true if the events matched and the sticky event was removed.
     */
    public boolean removeStickyEvent(Object event) {
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * 移除所有黏性事件
     */
    public void removeAllStickyEvents() {
        synchronized (stickyEvents) {
            stickyEvents.clear();//其实就是从内存中移除
        }
    }

    /**
     * 通过某一个事件来查找是否有观察者
     * 注意这里默认是会匹配父类和接口的
     * @param eventClass
     * @return
     */
    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        //这里并没有通过eventInheritance进行区别
        //而是默认了查找父类和接口，这个地方需要注意
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 尝试发送单个事件
     * @param event 当前需要发送的事件
     * @param postingState 当前线程的发送状态等参数
     */
    private void postSingleEvent(Object event, PostingThreadState postingState) throws Error {
        Class<?> eventClass = event.getClass();
        boolean subscriptionFound = false;
        if (eventInheritance) {//如果当前事件是一些观察者所观察的事件的子类，那么也进行回调
            //查找当前事件所有的父类和接口
            //这些父类和接口的观察者都会接收到当前事件
            List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                //将当前事件event发送给那些父类、接口、自身的观察者
                subscriptionFound |= postSingleEventForEventType(event, postingState, clazz);
            }
        } else {
            //单纯将当前事件event发送给当前event的观察者
            subscriptionFound = postSingleEventForEventType(event, postingState, eventClass);
        }
        if (!subscriptionFound) {//当前事件没有人处理
            if (logNoSubscriberMessages) {
                Log.d(TAG, "No subscribers registered for event " + eventClass);
            }
            //当前事件不能为预定义处理的两个事件
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class &&
                    eventClass != SubscriberExceptionEvent.class) {
                //当前允许发送NoSubscriberEvent事件，重新走发送流程
                post(new NoSubscriberEvent(this, event));
            }
        }
    }

    /**
     * 通过制定的观察者所观察的事件和当前发送的事件
     * @param event 当前发送的事件
     * @param postingState 当前线程的发送状态
     * @param eventClass 当前应该进行回调的方法对应的事件，这个可能为event的父类、接口，所以不一定和event是同一个class
     * @return true表示发送成功
     */
    private boolean postSingleEventForEventType(Object event, PostingThreadState postingState, Class<?> eventClass) {
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
            //通过当前观察者需要处理的事件获得观察元素
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        if (subscriptions != null && !subscriptions.isEmpty()) {
            for (Subscription subscription : subscriptions) {//遍历观察元素
                postingState.event = event;//记录当前线程所进行调用的事件
                postingState.subscription = subscription;//记录当前线程进行回调的观察元素
                boolean aborted = false;
                try {
                    postToSubscription(subscription, event, postingState.isMainThread);
                    aborted = postingState.canceled;//检查当前线程发送任务是否被取消
                } finally {
                    postingState.event = null;
                    postingState.subscription = null;
                    //这里返还了发送任务的canceled标记，意味着这个标记一般的用途是在串行事件中
                    //目前只能通过cancelEventDelivery处理，而且要求必须是POSTING处理
                    //说明这个设计的原意就是有的时候在某一个事件中不希望后续事件继续进行处理，比方说高优先级阻断低优先级
                    //此时在同一个线程中设置postingState.canceled为true，然后aborted为true，之后的事件就不会继续分发了
                    postingState.canceled = false;
                }
                if (aborted) {//如果当前线程的发送任务被取消，终止后续事件的发送
                    break;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 根据观察者的回调方法中定义的线程模式来进行不同的事件回调
     * @param subscription 当前需要进行回调的观察元素
     * @param event 当前需要回调的方法的参数，也是当前需要处理的事件
     * @param isMainThread 当前是否处于主线程
     */
    private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        switch (subscription.subscriberMethod.threadMode) {
            case POSTING://直接在当前post的线程中执行
                invokeSubscriber(subscription, event);
                break;
            case MAIN://在ui线程中执行
                if (isMainThread) {//当前post在主线程中，那么直接执行
                    invokeSubscriber(subscription, event);
                } else {//否则将当前事件交给主线程Poster等待执行
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            case BACKGROUND://在子线程中执行
                if (isMainThread) {//当前post在主线程中，将事件交给指定Poster等待在线程池中的某一个线程执行
                    backgroundPoster.enqueue(subscription, event);
                } else {//当前post在子线程中，直接在当前线程执行
                    invokeSubscriber(subscription, event);
                }
                break;
            case ASYNC://异步执行
                //一定在子线程中执行，但是不管当前在什么线程，都会进入线程池中等待调度执行
                asyncPoster.enqueue(subscription, event);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }

    /**
     * 在eventInheritance为true的基础上
     * 查找原始事件的父类和接口，这意味着如果有观察者观察这些父类和接口，
     * 那么对于这个原始事件来说他们也是观察的
     * @param eventClass 原始事件
     * @return 当前事件实际可以处理的事件集合，包括子类和接口
     */
    private static List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        synchronized (eventTypesCache) {
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            if (eventTypes == null) {
                eventTypes = new ArrayList<>();
                Class<?> clazz = eventClass;
                while (clazz != null) {//遍历
                    eventTypes.add(clazz);//首先是记录当前原始事件，如果有后续循环的话会处理会处理父类
                    //getInterfaces会返回当前类所实现的所有接口
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    clazz = clazz.getSuperclass();
                }
                eventTypesCache.put(eventClass, eventTypes);
            }
            return eventTypes;
        }
    }

    /** Recurses through super interfaces. */
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
        for (Class<?> interfaceClass : interfaces) {
            if (!eventTypes.contains(interfaceClass)) {
                eventTypes.add(interfaceClass);
                addInterfaces(eventTypes, interfaceClass.getInterfaces());//一个接口可能会继承其它接口
            }
        }
    }

    /**
     * 这个用于在Poster里面进行事件回调处理
     * 但是因为存在一些异步的Poster，所以说在进行回调之前还是要检查当前是否unregister
     */
    void invokeSubscriber(PendingPost pendingPost) {
        //记录当前需要进行回调处理的元素
        Object event = pendingPost.event;
        Subscription subscription = pendingPost.subscription;
        //将当前节点进行回收
        PendingPost.releasePendingPost(pendingPost);
        //有的时候异步进行回调的时候
        //可能当前的观察者已经被注销了
        //所以这里要进行检查
        if (subscription.active) {
            invokeSubscriber(subscription, event);
        }
    }

    /**
     * 进行方法的调用
     * @param subscription 当前需要回调的观察元素
     * @param event 当前需要回调的事件，也是回调函数的参数
     */
    void invokeSubscriber(Subscription subscription, Object event) {
        try {
            subscription.subscriberMethod.method.invoke(subscription.subscriber, event);
        } catch (InvocationTargetException e) {
            handleSubscriberException(subscription, event, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    /**
     * 当回调观察者指定函数的时候出现异常后的处理
     * @param subscription 当前观察元素
     * @param event 当前事件
     * @param cause 当前异常
     */
    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                Log.e(TAG, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass()
                        + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                Log.e(TAG, "Initial event " + exEvent.causingEvent + " caused exception in "
                        + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            if (throwSubscriberException) {
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                Log.e(TAG, "Could not dispatch event: " + event.getClass() + " to subscribing class "
                        + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event,
                        subscription.subscriber);
                post(exEvent);
            }
        }
    }

    /** For ThreadLocal, much faster to set (and get multiple values). */
    final static class PostingThreadState {
        //当前线程对应的发送事件列表
        final List<Object> eventQueue = new ArrayList<Object>();
        //当前线程是否处于事件发送中
        boolean isPosting;
        //当前线程是否为主线程
        boolean isMainThread;
        //当前posting中的观察元素
        Subscription subscription;
        //当前posting的事件
        Object event;
        //当前线程发送操作是否被取消
        boolean canceled;
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

    @Override
    public String toString() {
        return "EventBus[indexCount=" + indexCount + ", eventInheritance=" + eventInheritance + "]";
    }
}
