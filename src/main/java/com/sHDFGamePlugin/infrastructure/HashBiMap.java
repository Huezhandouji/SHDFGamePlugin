package com.sHDFGamePlugin.infrastructure;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 双向映射表：key 与 value 之间可互相查询（O(1)）。
 * <p>
 * 内部用两张 HashMap 维护正向与反向，保证 key 与 value 各自唯一。
 * 适用于"角色名 ↔ 按钮 GameItemId"这类需要双向查找且双方唯一的场景。
 * <p>
 * 建议不要使用 null 作为 key 或 value。
 */
public class HashBiMap<K, V> {

    private final Map<K, V> forward = new HashMap<>();
    private final Map<V, K> inverse = new HashMap<>();

    /**
     * 写入映射（严格模式）。
     *
     * @return true 写入成功；false 表示键已绑定其他值、或值已被其他键占用（不做任何改动）
     */
    public boolean put(K key, V value){
        //键已存在且绑定到其他值 → 失败
        V existing = forward.get(key);
        if(existing != null && !Objects.equals(existing, value)){
            return false;
        }
        //值已被其他键占用 → 失败
        K valueOwner = inverse.get(value);
        if(valueOwner != null && !Objects.equals(valueOwner, key)){
            return false;
        }
        //同键同值重复写入：数据无变化，直接成功
        if(existing != null){
            return true;
        }
        forward.put(key, value);
        inverse.put(value, key);
        return true;
    }

    /** 覆盖式写入：先移除占用该 value 的旧键，再写入；恒返回 true */
    public boolean forcePut(K key, V value){
        K occupiedKey = inverse.remove(value);
        if(occupiedKey != null && !Objects.equals(occupiedKey, key)){
            forward.remove(occupiedKey);
        }
        V oldValue = forward.put(key, value);
        if(oldValue != null && !Objects.equals(oldValue, value)){
            inverse.remove(oldValue);
        }
        inverse.put(value, key);
        return true;
    }

    /** 按 key 查询；不存在返回 null */
    public V getByKey(K key){
        return forward.get(key);
    }

    /** 按 value 反查 key；不存在返回 null */
    public K getByValue(V value){
        return inverse.get(value);
    }

    /** 按 key 移除映射，返回被移除的 value（不存在返回 null） */
    public V removeByKey(K key){
        V value = forward.remove(key);
        if(value != null){
            inverse.remove(value);
        }
        return value;
    }

    /** 按 value 移除映射，返回被移除的 key（不存在返回 null） */
    public K removeByValue(V value){
        K key = inverse.remove(value);
        if(key != null){
            forward.remove(key);
        }
        return key;
    }

    public boolean containsKey(Object key){
        return forward.containsKey(key);
    }

    public boolean containsValue(Object value){
        return inverse.containsKey(value);
    }

    public int size(){
        return forward.size();
    }

    public boolean isEmpty(){
        return forward.isEmpty();
    }

    public void clear(){
        forward.clear();
        inverse.clear();
    }

    /** 所有 key（不可修改视图） */
    public Set<K> keySet(){
        return Collections.unmodifiableSet(forward.keySet());
    }

    /** 所有 value（不可修改视图） */
    public Set<V> values(){
        return Collections.unmodifiableSet(inverse.keySet());
    }
}
