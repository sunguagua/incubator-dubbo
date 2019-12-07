/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.dubbo.common.threadlocal;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * InternalThreadLocal
 * A special variant of {@link ThreadLocal} that yields higher access performance when accessed from a
 * {@link InternalThread}.
 * <p></p>
 * Internally, a {@link InternalThread} uses a constant index in an array, instead of using hash code and hash table,
 * to look for a variable.  Although seemingly very subtle, it yields slight performance advantage over using a hash
 * table, and it is useful when accessed frequently.
 * <p>
 *     因为每一个InternalThreadLocal变量在初始化的时候index就已经确定了，获取或设置变量时可以直接使用，无需进行哈希运算。
 *     此外，ThreadLocal的Entry的key（ThreadLocal类型）使用了弱引用，
 *     以避免内存泄漏, 但是Entry中的value不是弱引用，还是存在内存泄漏的风险。而InternalThread没有这个问题
 * </p>
 *
 * This design is learning from {@see io.netty.util.concurrent.FastThreadLocal} which is in Netty.
 */
public class InternalThreadLocal<V> {

    // 要删除的变量的保存位置
    private static final int variablesToRemoveIndex = InternalThreadLocalMap.nextVariableIndex();

    private final int index;

    public InternalThreadLocal() {
        // 当前线程在实际保存数据的数组中的下标
        index = InternalThreadLocalMap.nextVariableIndex();
    }

    /**
     * Removes all {@link InternalThreadLocal} variables bound to the current thread.  This operation is useful when you
     * are in a container environment, and you don't want to leave the thread local variables in the threads you do not
     * manage.
     * 移除所有和当前线程绑定的变量, 容器中的线程池的线程是复用的，当我的某一次请求结束之后，如果不清理掉这些线程本地变量，就会污染下一次分配到使用这个线程的请求
     */
    @SuppressWarnings("unchecked")
    public static void removeAll() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return;
        }

        try {
            Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                Set<InternalThreadLocal<?>> variablesToRemove = (Set<InternalThreadLocal<?>>) v;
                InternalThreadLocal<?>[] variablesToRemoveArray =
                        variablesToRemove.toArray(new InternalThreadLocal[variablesToRemove.size()]);
                for (InternalThreadLocal<?> tlv : variablesToRemoveArray) {
                    tlv.remove(threadLocalMap);
                }
            }
        } finally {
            InternalThreadLocalMap.remove();
        }
    }

    /**
     * Returns the number of thread local variables bound to the current thread.
     */
    public static int size() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return 0;
        } else {
            return threadLocalMap.size();
        }
    }

    public static void destroy() {
        InternalThreadLocalMap.destroy();
    }

    @SuppressWarnings("unchecked")
    private static void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, InternalThreadLocal<?> variable) {
        // 从保存数据的数组中拿出一个位置来保存这个待移除变量的集合。这个方法在intialize或者set值的时候都会调用
        // 获取保存待移除变量的集合
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
        Set<InternalThreadLocal<?>> variablesToRemove;
        // 如果还没有设置，则初始化这个集合
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            // 使用IdentityHashMap是说比较两个元素是否相等，通过==而不是equals来比较
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<InternalThreadLocal<?>, Boolean>());
            threadLocalMap.setIndexedVariable(variablesToRemoveIndex, variablesToRemove);
        } else {
            variablesToRemove = (Set<InternalThreadLocal<?>>) v;
        }

        variablesToRemove.add(variable);
    }

    @SuppressWarnings("unchecked")
    private static void removeFromVariablesToRemove(InternalThreadLocalMap threadLocalMap, InternalThreadLocal<?> variable) {

        // 通过特定下标获取集合
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);

        // 若未初始化直接返回
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            return;
        }

        Set<InternalThreadLocal<?>> variablesToRemove = (Set<InternalThreadLocal<?>>) v;
        // 从集合中删除该InternalThreadLocal变量
        variablesToRemove.remove(variable);
    }

    /**
     * Returns the current value for the current thread
     */
    @SuppressWarnings("unchecked")
    public final V get() {
        // 获取map
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        // 通过下标获取变量值
        Object v = threadLocalMap.indexedVariable(index);
        // 若不为UNSET则直接返回
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        return initialize(threadLocalMap);
    }

    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            v = initialValue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 设置初始值到指定位置
        threadLocalMap.setIndexedVariable(index, v);
        addToVariablesToRemove(threadLocalMap, this);
        return v;
    }

    /**
     * Sets the value for the current thread.
     */
    public final void set(V value) {
        if (value == null || value == InternalThreadLocalMap.UNSET) {
            remove();
        } else {
            InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
            if (threadLocalMap.setIndexedVariable(index, value)) {
                addToVariablesToRemove(threadLocalMap, this);
            }
        }
    }

    /**
     * Sets the value to uninitialized; a proceeding call to get() will trigger a call to initialValue().
     */
    @SuppressWarnings("unchecked")
    public final void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Sets the value to uninitialized for the specified thread local map;
     * a proceeding call to get() will trigger a call to initialValue().
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final void remove(InternalThreadLocalMap threadLocalMap) {
        if (threadLocalMap == null) {
            return;
        }
        // 删除数组中对应位置的数据
        Object v = threadLocalMap.removeIndexedVariable(index);
        // 从待删除数据集合中删除
        removeFromVariablesToRemove(threadLocalMap, this);

        // 若删除的数据不是UNSET，则进行一些额外的处理
        if (v != InternalThreadLocalMap.UNSET) {
            try {
                onRemoval((V) v);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Returns the initial value for this thread-local variable.
     */
    protected V initialValue() throws Exception {
        return null;
    }

    /**
     * Invoked when this thread local variable is removed by {@link #remove()}.
     */
    protected void onRemoval(@SuppressWarnings("unused") V value) throws Exception {
    }
}
