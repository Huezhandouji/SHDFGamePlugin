package com.sHDFGamePlugin.infrastructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 游戏内部事件总线（静态）：按事件类型订阅/发布，{@link Subscription} 可取消。
 * <p>
 * 用于阶段/模块间解耦通信（玩家加入退出、炸弹状态、角色选择等）。
 */
public final class GameEventBus {

    private static final Map<Class<?>, List<Consumer<Object>>> listeners = new HashMap<>();

    /** 事件总线异常隔离日志（走服务端控制台，不依赖插件实例是否已就绪） */
    private static final Logger LOGGER = Logger.getLogger(GameEventBus.class.getName());

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

    /**
     * 发布事件：对每个订阅者做异常隔离。
     * <p>
     * 单个 handler 抛异常只记录日志（含事件类型与异常）并继续执行其余 handler，
     * 不会让一个订阅者的故障中断整条事件分发链（例如玩家退出事件因某阶段回调失败而丢失）。
     * 遍历语义不变：仍然先拷贝快照再遍历，允许 handler 在回调中订阅/退订，
     * 本次分发只影响快照中的订阅者。
     */
    public static <T> void publish(T event) {
        if(event == null) return;
        List<Consumer<Object>> handlers = listeners.get(event.getClass());
        if (handlers == null) return;
        //拷贝快照再遍历：允许处理器在回调中订阅/退订，不影响本次分发
        List<Consumer<Object>> snapshot = new ArrayList<>(handlers);
        for (Consumer<Object> handler : snapshot) {
            try {
                handler.accept(event);
            }
            catch (Throwable t) {
                LOGGER.severe("[GameEventBus] 事件 " + event.getClass().getName()
                        + " 的订阅者抛出异常, 已跳过该订阅者继续分发: " + t);
                t.printStackTrace();
            }
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