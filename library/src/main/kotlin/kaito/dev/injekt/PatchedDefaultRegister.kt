package kaito.dev.injekt

import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.InjektionException
import uy.kohesive.injekt.api.TypeReference
import uy.kohesive.injekt.api.erasedType
import uy.kohesive.injekt.api.get
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/**
 * Originally from https://github.com/kohesive/injekt/blob/d2866d0/core/src/main/kotlin/uy/kohesive/injekt/registry/default/DefaultRegistrar.kt
 * With https://github.com/inorichi/injekt/commit/65b0440 applied
 * And then cleaned up
 */
internal class PatchedDefaultRegister : InjektRegistrar {

    @Suppress("PrivatePropertyName", "ktlint:standard:property-naming")
    private val NOKEY = object {}

    internal data class Instance(val forWhatType: Type, val forKey: Any)

    // Get with type checked key
    private fun <K : Any, V : Any> Map<K, V>.getByKey(key: K): V? = get(key)

    private val existingValues = ConcurrentHashMap<Instance, Any>()
    private val threadedValues = object : ThreadLocal<HashMap<Instance, Any>>() {
        override fun initialValue(): HashMap<Instance, Any> {
            return hashMapOf()
        }
    }

    private val factories = ConcurrentHashMap<Type, () -> Any>()
    private val keyedFactories = ConcurrentHashMap<Type, (Any) -> Any>()

    internal data class LoggerInfo(val forWhatType: Type, val nameFactory: (String) -> Any, val classFactory: (Class<Any>) -> Any)

    @Volatile private var loggerFactory: LoggerInfo? = null

    // ==== Registry Methods by TypeReference ====================================================

    override fun <T : Any> addSingleton(forType: TypeReference<T>, singleInstance: T) {
        addSingletonFactory(forType) { singleInstance }
        // Load value into front cache
        get(forType)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> addSingletonFactory(forType: TypeReference<R>, factoryCalledOnce: () -> R) {
        factories[forType.type] = {
            val lazy = existingValues.getOrPut(Instance(forType.type, NOKEY)) {
                lazy { factoryCalledOnce() }
            }
            (lazy as Lazy<R>).value
        }
    }

    override fun <R : Any> addFactory(forType: TypeReference<R>, factoryCalledEveryTime: () -> R) {
        factories[forType.type] = factoryCalledEveryTime
    }

    override fun <R : Any> addPerThreadFactory(forType: TypeReference<R>, factoryCalledOncePerThread: () -> R) {
        factories[forType.type] = {
            threadedValues.get().getOrPut(Instance(forType.type, NOKEY)) { factoryCalledOncePerThread() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> addPerKeyFactory(forType: TypeReference<R>, factoryCalledPerKey: (K) -> R) {
        keyedFactories[forType.type] = { key ->
            existingValues.getOrPut(Instance(forType.type, key)) { factoryCalledPerKey(key as K) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> addPerThreadPerKeyFactory(forType: TypeReference<R>, factoryCalledPerKeyPerThread: (K) -> R) {
        keyedFactories[forType.type] = { key ->
            threadedValues.get().getOrPut(Instance(forType.type, key)) {
                factoryCalledPerKeyPerThread(key as K)
            }
        }
    }

    override fun <R : Any> addLoggerFactory(forLoggerType: TypeReference<R>, factoryByName: (String) -> R, factoryByClass: (Class<Any>) -> R) {
        loggerFactory = LoggerInfo(forLoggerType.type, factoryByName, factoryByClass)
    }

    override fun <O : Any, T : O> addAlias(existingRegisteredType: TypeReference<T>, otherAncestorOrInterface: TypeReference<O>) {
        // factories existing or not, and data type compatibility is checked in the InjektRegistrar interface default methods
        val existingFactory = factories.getByKey(existingRegisteredType.type)
        val existingKeyedFactory = keyedFactories.getByKey(existingRegisteredType.type)

        if (existingFactory != null) {
            factories.put(otherAncestorOrInterface.type, existingFactory)
        }
        if (existingKeyedFactory != null) {
            keyedFactories.put(otherAncestorOrInterface.type, existingKeyedFactory)
        }
    }
    override fun <T : Any> hasFactory(forType: TypeReference<T>): Boolean {
        return factories.getByKey(forType.type) != null || keyedFactories.getByKey(forType.type) != null
    }

    // ==== Factory Methods ======================================================================

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstance(forType: Type): R {
        val factory = factories.getByKey(forType)
            ?: throw InjektionException("No registered instance or factory for type $forType")
        return factory.invoke() as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrElse(forType: Type, default: R): R {
        val factory = factories.getByKey(forType) ?: return default
        return factory.invoke() as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrElse(forType: Type, default: () -> R): R {
        val factory = factories.getByKey(forType) ?: return default()
        return factory.invoke() as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrNull(forType: Type): R? {
        val factory = factories.getByKey(forType) ?: return null
        return factory.invoke() as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstance(forType: Type, key: K): R {
        val factory = keyedFactories.getByKey(forType)
            ?: throw InjektionException("No registered keyed factory for type $forType")
        return factory.invoke(key) as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: R): R {
        val factory = keyedFactories.getByKey(forType) ?: return default
        return factory.invoke(key) as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: () -> R): R {
        val factory = keyedFactories.getByKey(forType) ?: return default()
        return factory.invoke(key) as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstanceOrNull(forType: Type, key: K): R? {
        val factory = keyedFactories.getByKey(forType) ?: return null
        return factory.invoke(key) as R
    }

    private fun assertLogger(expectedLoggerType: Type) {
        if (loggerFactory == null) {
            throw InjektionException("Cannot call getLogger() -- A logger factory has not been registered with Injekt")
        } else if (!loggerFactory!!.forWhatType.erasedType().isAssignableFrom(expectedLoggerType.erasedType())) {
            throw InjektionException("Logger factories registered with Injekt indicate they return type ${loggerFactory!!.forWhatType} but current injekt target is expecting type $expectedLoggerType")
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getLogger(expectedLoggerType: Type, byName: String): R {
        assertLogger(expectedLoggerType)
        // If casting to wrong type, let it die with casting exception
        return loggerFactory!!.nameFactory(byName) as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, T : Any> getLogger(expectedLoggerType: Type, forClass: Class<T>): R {
        assertLogger(expectedLoggerType)
        // If casting to wrong type, let it die with casting exception
        return loggerFactory!!.classFactory(forClass.erasedType()) as R
    }
}
