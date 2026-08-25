package com.sHDFGamePlugin.infrastructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class GameEventBus {

    private static final Map<Class<?>, List<Consumer<Object>>> listeners = new HashMap<>();

    private GameEventBus() {}

    @SuppressWarnings("unchecked")
    public static <T> Subscription subscribe(Class<T> eventType, Consumer<T> handler) {
        List<Consumer<Object>> list = listeners.computeIfAbsent(eventType, k -> new ArrayList<>());
        Consumer<Object> h = (Consumer<Object>) handler;
        list.add(h);
        return new Subscription(eventType, h);
    }

    private static void unsubscribe(Class<?> eventType, Consumer<Object> handler) {
        List<Consumer<Object>> list = listeners.get(eventType);
        if (list == null) return;
        list.remove(handler);
        if (list.isEmpty()) {
            listeners.remove(eventType);
        }
    }

    public static <T> void publish(T event) {
        List<Consumer<Object>> handlers = listeners.get(event.getClass());
        if (handlers == null) return;
        List<Consumer<Object>> snapshot = new ArrayList<>(handlers);
        for (Consumer<Object> handler : snapshot) {
            handler.accept(event);
        }
    }

    public static void unsubscribeAll() {
        listeners.clear();
    }

    public static class Subscription {
        private final Class<?> eventType;
        private final Consumer<Object> handler;
        private boolean isUnsubscribed = false;

        private Subscription(Class<?> eventType, Consumer<Object> handler) {
            this.eventType = eventType;
            this.handler = handler;
        }

        public void unsubscribe() {
            if (!isUnsubscribed) {
                GameEventBus.unsubscribe(eventType, handler);
                isUnsubscribed = true;
            }
        }

        public boolean isUnsubscribed() {
            return isUnsubscribed;
        }
    }
}