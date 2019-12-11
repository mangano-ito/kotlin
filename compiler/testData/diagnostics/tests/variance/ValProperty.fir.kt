import kotlin.reflect.KProperty

interface In<in T>
interface Out<out T>
interface Inv<T>

class Delegate<T> {
    operator fun getValue(t: Any, p: KProperty<*>): T = null!!
    operator fun setValue(t: Any, p: KProperty<*>, value: T) {}
}

fun <T> getT(): T = null!!

abstract class Test<in I, out O, P> {
    abstract val type1: I
    abstract val type2: O
    abstract val type3: P
    abstract val type4: In<I>
    abstract val type5: In<O>

    val implicitType1 = getT<I>()
    val implicitType2 = getT<O>()
    val implicitType3 = getT<P>()
    val implicitType4 = getT<In<I>>()
    val implicitType5 = getT<In<O>>()

    val delegateType1 by Delegate<I>()
    val delegateType2 by Delegate<O>()
    val delegateType3 by Delegate<P>()
    val delegateType4 by Delegate<In<I>>()
    val delegateType5 by Delegate<In<O>>()

    abstract val I.receiver1: Int
    abstract val O.receiver2: Int
    abstract val P.receiver3: Int
    abstract val In<I>.receiver4: Int
    abstract val In<O>.receiver5: Int

    val <X : I> X.typeParameter1: Int get() = 0
    val <X : O> X.typeParameter2: Int get() = 0
    val <X : P> X.typeParameter3: Int get() = 0
    val <X : In<I>> X.typeParameter4: Int get() = 0
    val <X : In<O>> X.typeParameter5: Int get() = 0

    val <X> X.typeParameter6: Int where X : I get() = 0
    val <X> X.typeParameter7: Int where X : O get() = 0
    val <X> X.typeParameter8: Int where X : P get() = 0
    val <X> X.typeParameter9: Int where X : In<I> get() = 0
    val <X> X.typeParameter0: Int where X : In<O> get() = 0
}
