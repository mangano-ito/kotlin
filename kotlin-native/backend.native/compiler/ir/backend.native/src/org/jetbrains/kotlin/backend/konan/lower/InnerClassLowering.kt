/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.callsSuper
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

internal class OuterThisLowering(val context: Context) : ClassLoweringPass {
    override fun lower(irClass: IrClass) {
        if (!irClass.isInner) return
        irClass.transformChildrenVoid(Transformer(irClass, irClass))
    }

    fun lower(irBody: IrBody, container: IrDeclaration) {
        var irClass = container as? IrClass
        if (irClass == null) {
            var parent = container.parent
            while (parent !is IrPackageFragment) {
                irClass = parent as? IrClass
                if (irClass != null) break
                parent = (parent as IrDeclaration).parent
            }
        }
        if (irClass == null || !irClass.isInner) return

        irBody.transformChildrenVoid(Transformer(irClass, container))
    }

    private inner class Transformer(val irClass: IrClass, val container: IrDeclaration) : IrElementTransformerVoidWithContext() {
        override fun visitClassNew(declaration: IrClass): IrStatement {
            // Skip nested.
            return declaration
        }

        override fun visitGetValue(expression: IrGetValue): IrExpression {
            expression.transformChildrenVoid(this)

            val implicitThisClass = (expression.symbol.owner.parent as? IrClass) ?: return expression

            if (implicitThisClass == irClass) return expression

            val parentScopeSymbols = listOf(container.symbol) + allScopes.map { it.scope.scopeOwnerSymbol }
            var functionSymbol: IrFunctionSymbol? = null
            for (i in parentScopeSymbols.size - 1 downTo 0) {
                val currentSymbol = parentScopeSymbols[i] as? IrFunctionSymbol ?: break
                functionSymbol = currentSymbol
            }
            if (functionSymbol == null) return expression

            val startOffset = expression.startOffset
            val endOffset = expression.endOffset
            val origin = expression.origin

            var irThis: IrExpression
            var innerClass: IrClass
            if ((functionSymbol as? IrConstructorSymbol)?.owner?.parentAsClass == irClass) {
                // For constructor we have outer class as dispatchReceiverParameter.
                innerClass = irClass.parent as? IrClass ?: error("No containing class for inner class ${irClass.render()}")
                val thisParameter = functionSymbol.owner.dispatchReceiverParameter!!
                irThis = IrGetValueImpl(startOffset, endOffset, thisParameter.type, thisParameter.symbol, origin)
            } else {
                innerClass = irClass

                val currentFunctionReceiver = functionSymbol.owner.dispatchReceiverParameter
                val thisParameter =
                        if (currentFunctionReceiver?.type?.classifierOrNull == irClass.symbol)
                            currentFunctionReceiver
                        else
                            irClass.thisReceiver!!

                irThis = IrGetValueImpl(startOffset, endOffset, thisParameter.type, thisParameter.symbol, origin)
            }

            while (innerClass != implicitThisClass) {
                if (!innerClass.isInner) {
                    // Captured 'this' unrelated to inner classes nesting hierarchy, leave it as is -
                    // should be transformed by closures conversion.
                    return expression
                }

                val outerThisField = context.specialDeclarationsFactory.getOuterThisField(innerClass)
                irThis = IrGetFieldImpl(
                        startOffset, endOffset,
                        outerThisField.symbol, outerThisField.type,
                        irThis,
                        origin
                )

                val outer = innerClass.parent
                innerClass = outer as? IrClass
                        ?: throw AssertionError("Unexpected containing declaration for inner class ${innerClass.dump()}: $outer")
            }

            return irThis
        }
    }
}

internal class InnerClassLowering(val context: Context) : ClassLoweringPass {
    override fun lower(irClass: IrClass) {
        OuterThisLowering(context).lower(irClass)
        InnerClassTransformer(irClass).lowerInnerClass()
    }

    companion object {
        fun addOuterThisField(declarations: MutableList<IrDeclaration>, irClass: IrClass, context: Context): IrField {
            val outerThisField = context.specialDeclarationsFactory.getOuterThisField(irClass)
            declarations += outerThisField
            return outerThisField
        }
    }

    private inner class InnerClassTransformer(val irClass: IrClass) {
        lateinit var outerThisFieldSymbol: IrFieldSymbol

        fun lowerInnerClass() {
            if (!irClass.isInner) return

            createOuterThisField()
            lowerConstructors()
        }

        private fun createOuterThisField() {
            val outerThisField = addOuterThisField(irClass.declarations, irClass, context)
            outerThisFieldSymbol = outerThisField.symbol
        }

        private fun lowerConstructors() {
            irClass.declarations.transformFlat { irMember ->
                if (irMember is IrConstructor)
                    listOf(lowerConstructor(irMember))
                else
                    null
            }
        }

        private fun lowerConstructor(irConstructor: IrConstructor): IrConstructor {
            if (irConstructor.callsSuper(context.irBuiltIns)) {
                // Initializing constructor: initialize 'this.this$0' with '$outer'.
                val blockBody = irConstructor.body as? IrBlockBody
                        ?: throw AssertionError("Unexpected constructor body: ${irConstructor.body}")
                val startOffset = irConstructor.startOffset
                val endOffset = irConstructor.endOffset
                val thisReceiver = irClass.thisReceiver!!
                val outerReceiver = irConstructor.dispatchReceiverParameter!!
                blockBody.statements.add(
                        0,
                        IrSetFieldImpl(
                                startOffset, endOffset, outerThisFieldSymbol,
                                IrGetValueImpl(startOffset, endOffset, thisReceiver.type, thisReceiver.symbol),
                                IrGetValueImpl(startOffset, endOffset, outerReceiver.type, outerReceiver.symbol),
                                context.irBuiltIns.unitType
                        )
                )
            }

            return irConstructor
        }
    }
}

