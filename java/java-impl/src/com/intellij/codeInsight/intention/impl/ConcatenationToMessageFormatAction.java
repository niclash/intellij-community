/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package com.intellij.codeInsight.intention.impl;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.codeInsight.CodeInsightUtilBase;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiConcatenationUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ven
 */
public class ConcatenationToMessageFormatAction implements IntentionAction {
  @Override
  @NotNull
  public String getFamilyName() {
    return CodeInsightBundle.message("intention.replace.concatenation.with.formatted.output.family");
  }

  @Override
  @NotNull
  public String getText() {
    return CodeInsightBundle.message("intention.replace.concatenation.with.formatted.output.text");
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
    if (!CodeInsightUtilBase.prepareFileForWrite(file)) return;
    final PsiElement element = findElementAtCaret(editor, file);
    PsiPolyadicExpression concatenation = getEnclosingLiteralConcatenation(element);
    if (concatenation == null) return;
    StringBuilder formatString = new StringBuilder();
    List<PsiExpression> args = new ArrayList<PsiExpression>();
    buildMessageFormatString(concatenation, formatString, args);

    final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
    PsiMethodCallExpression call = (PsiMethodCallExpression)
      factory.createExpressionFromText("java.text.MessageFormat.format()", concatenation);
    PsiExpressionList argumentList = call.getArgumentList();
    PsiExpression formatArgument = factory.createExpressionFromText("\"" + formatString.toString() + "\"", null);
    argumentList.add(formatArgument);
    if (PsiUtil.isLanguageLevel5OrHigher(file)) {
      for (PsiExpression arg : args) {
        argumentList.add(arg);
      }
    }
    else {
      final PsiNewExpression arrayArg = (PsiNewExpression)factory.createExpressionFromText("new java.lang.Object[]{}", null);
      final PsiArrayInitializerExpression arrayInitializer = arrayArg.getArrayInitializer();
      assert arrayInitializer != null;
      for (PsiExpression arg : args) {
        arrayInitializer.add(arg);
      }
      argumentList.add(arrayArg);
    }
    call = (PsiMethodCallExpression) JavaCodeStyleManager.getInstance(project).shortenClassReferences(call);
    call = (PsiMethodCallExpression) CodeStyleManager.getInstance(element.getManager().getProject()).reformat(call);
    concatenation.replace(call);
  }

  public static void buildMessageFormatString(PsiExpression expression,
                                              StringBuilder formatString,
                                              List<PsiExpression> args)
    throws IncorrectOperationException {
    PsiConcatenationUtil.buildFormatString(expression, formatString, args, false);

  }

  private static void appendArgument(List<PsiExpression> args, PsiExpression argument, StringBuilder formatString) throws IncorrectOperationException {
    formatString.append("{").append(args.size()).append("}");
    args.add(getBoxedArgument(argument));
  }

  private static PsiExpression getBoxedArgument(PsiExpression arg) throws IncorrectOperationException {
    arg = PsiUtil.deparenthesizeExpression(arg);
    assert arg != null;
    if (PsiUtil.isLanguageLevel5OrHigher(arg)) {
      return arg;
    }
    final PsiType type = arg.getType();
    if (!(type instanceof PsiPrimitiveType) || type.equals(PsiType.NULL)) {
      return arg;
    }
    final PsiPrimitiveType primitiveType = (PsiPrimitiveType)type;
    final String boxedQName = primitiveType.getBoxedTypeName();
    if (boxedQName == null) {
      return arg;
    }
    final GlobalSearchScope resolveScope = arg.getResolveScope();
    final PsiElementFactory factory = JavaPsiFacade.getElementFactory(arg.getProject());
    final PsiJavaCodeReferenceElement ref = factory.createReferenceElementByFQClassName(boxedQName, resolveScope);
    final PsiNewExpression newExpr = (PsiNewExpression)factory.createExpressionFromText("new A(b)", null);
    final PsiElement classRef = newExpr.getClassReference();
    assert classRef != null;
    classRef.replace(ref);
    final PsiExpressionList argumentList = newExpr.getArgumentList();
    assert argumentList != null;
    argumentList.getExpressions()[0].replace(arg);
    return newExpr;
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
    if (PsiUtil.getLanguageLevel(file).compareTo(LanguageLevel.JDK_1_4) < 0) return false;
    final PsiElement element = findElementAtCaret(editor, file);
    PsiPolyadicExpression binaryExpression = getEnclosingLiteralConcatenation(element);
    return binaryExpression != null && !isInsideAnnotation(binaryExpression);
  }

  private static boolean isInsideAnnotation(PsiElement element) {
    return PsiTreeUtil.getParentOfType(element, PsiNameValuePair.class, PsiArrayInitializerMemberValue.class) != null;
  }

  @Nullable
  private static PsiElement findElementAtCaret(Editor editor, PsiFile file) {
    return file.findElementAt(editor.getCaretModel().getOffset());
  }

  @Nullable
  private static PsiPolyadicExpression getEnclosingLiteralConcatenation(final PsiElement element) {
    PsiPolyadicExpression binaryExpression = PsiTreeUtil.getParentOfType(element, PsiPolyadicExpression.class, false, PsiMember.class);
    if (binaryExpression == null) return null;
    final PsiClassType stringType = PsiType.getJavaLangString(element.getManager(), element.getResolveScope());
    if (!stringType.equals(binaryExpression.getType())) return null;
    while (true) {
      final PsiElement parent = binaryExpression.getParent();
      if (!(parent instanceof PsiPolyadicExpression)) return binaryExpression;
      PsiPolyadicExpression parentBinaryExpression = (PsiPolyadicExpression)parent;
      if (!stringType.equals(parentBinaryExpression.getType())) return binaryExpression;
      binaryExpression = parentBinaryExpression;
    }
  }

  @Override
  public boolean startInWriteAction() {
    return true;
  }
}
