package io.objectbox.reactive;

import java.util.concurrent.ExecutorService;

import io.objectbox.annotation.apihint.Internal;

/**
 * Builds a {@link DataSubscription} for a {@link DataObserver} passed via {@link #observer(DataObserver)}.
 * Note that the call to {@link #observer(DataObserver)} is mandatory to create the subscription -
 * if you forget it, nothing will happen.
 * <p>
 * When subscribing to a data source such as {@link io.objectbox.query.Query}, this builder allows to configure:
 * <ul>
 * <li>weakly referenced observer via {@link #weak()}</li>
 * <li>a data transform operation via {@link #transform(Transformer)}</li>
 * <li>calling the observer using a custom {@link Scheduler} (e.g. Android main thread) via {@link #on(Scheduler)}</li>
 * </ul>
 *
 * @param <T> The data type the {@link DataObserver} subscribes to.
 */
public class SubscriptionBuilder<T> {
    private DataPublisher<T> publisher;
    private final Object publisherParam;
    private final ExecutorService threadPool;
    private DataObserver<T> observer;
    //    private Runnable firstRunnable;
    private boolean weak;
    private Transformer<T, Object> transformer;
    private Scheduler scheduler;
    private ErrorObserver errorObserver;
//    private boolean sync;


    @Internal
    public SubscriptionBuilder(DataPublisher<T> publisher, Object param, ExecutorService threadPool) {
        this.publisher = publisher;
        publisherParam = param;
        this.threadPool = threadPool;
    }

//    public Observable<T> runFirst(Runnable firstRunnable) {
//        if (firstRunnable != null) {
//            throw new IllegalStateException("Only one asyncRunnable allowed");
//        }
//        this.firstRunnable = firstRunnable;
//        return this;
//    }

    /**
     * Uses a weak reference for the observer.
     * It is still advised to remove observers explicitly if possible: relying on the garbage collection may cause
     * non-deterministic timing. Until the weak reference is actually cleared by GC, it may still receive notifications.
     */
    public SubscriptionBuilder<T> weak() {
        weak = true;
        return this;
    }

//    public Observable<T> sync() {
//        sync = true;
//        return this;
//    }

    /**
     * Transforms the original data from the publisher to something that is more helpful to your application.
     * The transformation is done in an asynchronous thread.
     * The observer will be called in the same asynchronous thread unless a Scheduler is defined using
     * {@link #on(Scheduler)}.
     * <p>
     * This is roughly equivalent to the map operator as known in Rx and Kotlin.
     *
     * @param <TO> The class the data is transformed to
     */
    public <TO> SubscriptionBuilder<TO> transform(final Transformer<T, TO> transformer) {
        if (this.transformer != null) {
            throw new IllegalStateException("Only one transformer allowed");
        }
        this.transformer = (Transformer<T, Object>) transformer;
        return (SubscriptionBuilder<TO>) this;
    }

    public SubscriptionBuilder<T> onError(ErrorObserver errorObserver) {
        if (this.errorObserver != null) {
            throw new IllegalStateException("Only one errorObserver allowed");
        }
        this.errorObserver = errorObserver;
        return this;
    }

    public SubscriptionBuilder<T> on(Scheduler scheduler) {
        if (this.scheduler != null) {
            throw new IllegalStateException("Only one scheduler allowed");
        }
        this.scheduler = scheduler;
        return this;
    }

    public DataSubscription observer(final DataObserver<T> observer) {
        WeakDataObserver<T> weakObserver = null;
        if (weak) {
            weakObserver = new WeakDataObserver<>(observer);
            this.observer = weakObserver;
        } else {
            this.observer = observer;
        }
        DataSubscriptionImpl subscription = new DataSubscriptionImpl(publisher, publisherParam, observer);
        if (weakObserver != null) {
            weakObserver.setSubscription(subscription);
        }

        // TODO FIXME when an observer subscribes twice, it currently won't be added, but we return a new subscription
        if (transformer == null && scheduler == null) {
            publisher.subscribe(observer, publisherParam);
        } else {
            publisher.subscribe(new ActionObserver(subscription), publisherParam);
        }
        return subscription;
    }

    class ActionObserver implements DataObserver<T> {
        private final DataSubscriptionImpl subscription;
        private SchedulerRunOnError schedulerRunOnError;
        private SchedulerRunOnChange schedulerRunOnData;

        public ActionObserver(DataSubscriptionImpl subscription) {
            this.subscription = subscription;
            if (scheduler != null) {
                schedulerRunOnData = new SchedulerRunOnChange();
                if (errorObserver != null) {
                    schedulerRunOnError = new SchedulerRunOnError();
                }
            }
        }

        @Override
        public void onData(final T data) {
            if (transformer != null) {
                transformAndContinue(data);
            } else {
                callOnData(data);
            }
        }

        private void transformAndContinue(final T data) {
            threadPool.submit(new Runnable() {
                @Override
                public void run() {
                    if (subscription.isCanceled()) {
                        return;
                    }
                    try {
                        // Type erasure FTW
                        T result = (T) transformer.transform(data);
                        callOnData(result);
                    } catch (Throwable th) {
                        if (errorObserver != null) {
                            if (!subscription.isCanceled()) {
                                if (scheduler != null) {
                                    scheduler.run(schedulerRunOnError, th);
                                } else {
                                    errorObserver.onError(th);
                                }
                            }
                        } else {
                            throw new RuntimeException("Transformer failed without an ErrorObserver set", th);
                        }
                    }
                }
            });
        }

        void callOnData(final T result) {
            if (!subscription.isCanceled()) {
                if (scheduler != null) {
                    scheduler.run(schedulerRunOnData, result);
                } else {
                    observer.onData(result);
                }
            }
        }

        class SchedulerRunOnChange implements RunWithParam<T> {
            @Override
            public void run(T data) {
                if (!subscription.isCanceled()) {
                    observer.onData(data);
                }
            }
        }

        class SchedulerRunOnError implements RunWithParam<Throwable> {
            @Override
            public void run(Throwable data) {
                if (!subscription.isCanceled()) {
                    errorObserver.onError(data);
                }
            }
        }
    }

}
