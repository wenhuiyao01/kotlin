/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getLastParentOfTypeInRow
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypesAndPredicate
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.bindingContextUtil.isUsedAsExpression
import org.jetbrains.kotlin.resolve.calls.callUtil.getType
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactory
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.typeUtil.isNullabilityMismatch

class SurroundWithNullCheckFix(
        expression: KtExpression,
        val nullableExpression: KtExpression
) : KotlinQuickFixAction<KtExpression>(expression), HighPriorityAction {

    override fun getFamilyName() = text

    override fun getText() = "Surround with null check"

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val factory = KtPsiFactory(element)
        val surrounded = factory.createExpressionByPattern("if ($0 != null) { $1 }", nullableExpression, element)
        element.replace(surrounded)
    }

    companion object : KotlinSingleIntentionActionFactory() {

        override fun createAction(diagnostic: Diagnostic): IntentionAction? {
            val element = diagnostic.psiElement
            val expressionParent = element.getParentOfType<KtExpression>(strict = element is KtOperationReferenceExpression) ?: return null
            val context = expressionParent.analyze(BodyResolveMode.PARTIAL)

            val parent = element.parent
            val nullableExpression =
                    when (parent) {
                        is KtDotQualifiedExpression -> parent.receiverExpression
                        is KtBinaryExpression -> parent.left
                        is KtCallExpression -> parent.calleeExpression
                        else -> return null
                    } as? KtReferenceExpression ?: return null

            if (!nullableExpression.isStable(context)) return null

            val expressionTarget = expressionParent.getParentOfTypesAndPredicate(strict = false, parentClasses = KtExpression::class.java) {
                !it.isUsedAsExpression(context)
            } ?: return null
            // Surround declaration (even of local variable) with null check is generally a bad idea
            if (expressionTarget is KtDeclaration) return null

            return SurroundWithNullCheckFix(expressionTarget, nullableExpression)
        }
    }

    object IteratorOnNullableFactory : KotlinSingleIntentionActionFactory() {

        override fun createAction(diagnostic: Diagnostic): IntentionAction? {
            val nullableExpression = diagnostic.psiElement as? KtReferenceExpression ?: return null
            val forExpression = nullableExpression.parent?.parent as? KtForExpression ?: return null
            if (forExpression.parent !is KtBlockExpression) return null

            if (!nullableExpression.isStable()) return null

            return SurroundWithNullCheckFix(forExpression, nullableExpression)
        }
    }

    object TypeMismatchFactory : KotlinSingleIntentionActionFactory() {

        override fun createAction(diagnostic: Diagnostic): IntentionAction? {
            val typeMismatch = Errors.TYPE_MISMATCH.cast(diagnostic)
            val nullableExpression = typeMismatch.psiElement as? KtReferenceExpression ?: return null
            val argument = nullableExpression.parent as? KtValueArgument ?: return null
            val call = argument.getParentOfType<KtCallExpression>(true) ?: return null

            val rootCall = call.getLastParentOfTypeInRow<KtQualifiedExpression>() ?: call
            if (rootCall.parent !is KtBlockExpression) return null

            if (!isNullabilityMismatch(expected = typeMismatch.a, actual = typeMismatch.b)) return null

            if (!nullableExpression.isStable()) return null

            return SurroundWithNullCheckFix(rootCall, nullableExpression)
        }
    }
}

private fun KtExpression.isStable(context: BindingContext = this.analyze()): Boolean {
    val nullableType = this.getType(context) ?: return false
    val containingDescriptor = this.getResolutionScope(context, this.getResolutionFacade()).ownerDescriptor
    return DataFlowValueFactory.createDataFlowValue(this, nullableType, context, containingDescriptor).isStable
}
