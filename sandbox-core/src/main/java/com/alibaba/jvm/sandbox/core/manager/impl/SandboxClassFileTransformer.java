package com.alibaba.jvm.sandbox.core.manager.impl;

import com.alibaba.jvm.sandbox.api.event.Event;
import com.alibaba.jvm.sandbox.api.event.Event.Type;
import com.alibaba.jvm.sandbox.api.listener.EventListener;
import com.alibaba.jvm.sandbox.core.enhance.EventEnhancer;
import com.alibaba.jvm.sandbox.core.util.ObjectIDs;
import com.alibaba.jvm.sandbox.core.util.SandboxClassUtils;
import com.alibaba.jvm.sandbox.core.util.SandboxProtector;
import com.alibaba.jvm.sandbox.core.util.matcher.Matcher;
import com.alibaba.jvm.sandbox.core.util.matcher.MatchingResult;
import com.alibaba.jvm.sandbox.core.util.matcher.UnsupportedMatcher;
import com.alibaba.jvm.sandbox.core.util.matcher.structure.ClassStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;

import static com.alibaba.jvm.sandbox.core.util.matcher.structure.ClassStructureFactory.createClassStructure;

/**
 * 沙箱类形变器
 *
 * @author luanjia@taobao.com
 */
public class SandboxClassFileTransformer implements ClassFileTransformer {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * SANDBOX限定前缀
     */
    public static final String SANDBOX_SPECIAL_PREFIX = "$$SANDBOX$";

    private final int watchId;
    private final String uniqueId;
    private final Matcher matcher;
    private final EventListener eventListener;
    private final boolean isEnableUnsafe;
    private final Event.Type[] eventTypeArray;

    private final String namespace;
    private final int listenerId;
    private final AffectStatistic affectStatistic = new AffectStatistic();
    private final boolean isNativeSupported;
    private final String nativePrefix;

    SandboxClassFileTransformer(final int watchId,
                                final String uniqueId,
                                final Matcher matcher,
                                final EventListener eventListener,
                                final boolean isEnableUnsafe,
                                final Type[] eventTypeArray,
                                final String namespace,
                                final boolean isNativeSupported) {
        this.watchId = watchId;
        this.uniqueId = uniqueId;
        this.matcher = matcher;
        this.eventListener = eventListener;
        this.isEnableUnsafe = isEnableUnsafe;
        this.eventTypeArray = eventTypeArray;
        this.namespace = namespace;
        this.listenerId = ObjectIDs.instance.identity(eventListener);
        this.isNativeSupported = isNativeSupported;
        this.nativePrefix = String.format("%s$%s$%s", SANDBOX_SPECIAL_PREFIX, namespace, watchId);
    }

    // 获取当前类结构
    private ClassStructure getClassStructure(final ClassLoader loader,
                                             final Class<?> classBeingRedefined,
                                             final byte[] srcByteCodeArray) {
        return null == classBeingRedefined
                ? createClassStructure(srcByteCodeArray, loader)
                : createClassStructure(classBeingRedefined);
    }

    @Override
    public byte[] transform(final ClassLoader loader,
                            final String internalClassName,
                            final Class<?> classBeingRedefined,
                            final ProtectionDomain protectionDomain,
                            final byte[] srcByteCodeArray) {

        SandboxProtector.instance.enterProtecting();
        try {

            // 这里过滤掉Sandbox所需要的类|来自SandboxClassLoader所加载的类|来自ModuleJarClassLoader加载的类
            // 防止ClassCircularityError的发生
            if (SandboxClassUtils.isComeFromSandboxFamily(internalClassName, loader)) {
                return null;
            }

            // 如果未开启unsafe开关，是不允许增强来自BootStrapClassLoader的类
            if (!isEnableUnsafe
                    && null == loader) {
                logger.debug("transform ignore {}, class from bootstrap but unsafe.enable=false.", internalClassName);
                return null;
            }

            // 匹配类是否符合要求，如果一个行为都没匹配上也不用继续了
            final MatchingResult result = new UnsupportedMatcher(loader, isEnableUnsafe, isNativeSupported)
                    .and(matcher)
                    .matching(getClassStructure(loader, classBeingRedefined, srcByteCodeArray));
            if (!result.isMatched()) {
                logger.debug("transform ignore {}, no behaviors matched in loader={}", internalClassName, loader);
                return null;
            }

            // 找到匹配的类和方法，开始增强
            return _transform(
                    result,
                    loader,
                    internalClassName,
                    srcByteCodeArray
            );


        } catch (Throwable cause) {
            logger.warn("sandbox transform {} in loader={}; failed, module={} at watch={}, will ignore this transform.",
                    internalClassName,
                    loader,
                    uniqueId,
                    watchId,
                    cause
            );
            return null;
        } finally {
            SandboxProtector.instance.exitProtecting();
        }
    }

    private byte[] _transform(final MatchingResult result,
                              final ClassLoader loader,
                              final String internalClassName,
                              final byte[] srcByteCodeArray) {

        // 匹配到的方法签名
        final Set<String> behaviorSignCodes = result.getBehaviorSignCodes();

        // 开始进行类匹配
        try {
            final byte[] toByteCodeArray = new EventEnhancer(nativePrefix).toByteCodeArray(
                    loader,
                    srcByteCodeArray,
                    behaviorSignCodes,
                    namespace,
                    listenerId,
                    eventTypeArray
            );
            if (srcByteCodeArray == toByteCodeArray) {
                logger.debug("transform ignore {}, nothing changed in loader={}", internalClassName, loader);
                return null;
            }

            // statistic affect
            affectStatistic.statisticAffect(loader, internalClassName, behaviorSignCodes);

            logger.info("transform {} finished, by module={} in loader={}", internalClassName, uniqueId, loader);
            return toByteCodeArray;
        } catch (Throwable cause) {
            logger.warn("transform {} failed, by module={} in loader={}", internalClassName, uniqueId, loader, cause);
            return null;
        }
    }


    /**
     * 获取观察ID
     *
     * @return 观察ID
     */
    int getWatchId() {
        return watchId;
    }

    /**
     * 获取事件监听器
     *
     * @return 事件监听器
     */
    EventListener getEventListener() {
        return eventListener;
    }

    /**
     * 获取事件监听器ID
     *
     * @return 事件监听器ID
     */
    int getListenerId() {
        return listenerId;
    }

    /**
     * 获取本次匹配器
     *
     * @return 匹配器
     */
    Matcher getMatcher() {
        return matcher;
    }

    /**
     * 获取本次监听事件类型数组
     *
     * @return 本次监听事件类型数组
     */
    Event.Type[] getEventTypeArray() {
        return eventTypeArray;
    }

    /**
     * 获取本次增强的影响统计
     *
     * @return 本次增强的影响统计
     */
    public AffectStatistic getAffectStatistic() {
        return affectStatistic;
    }

    /**
     * 获取本次增强的native方法前缀，
     * 根据JVM规范，每个ClassFileTransformer必须拥有自己的native方法前缀
     * @return native方法前缀
     */
    public String getNativePrefix() {
        return nativePrefix;
    }

}
