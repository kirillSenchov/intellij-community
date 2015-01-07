/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.intellij.psi;

import com.intellij.openapi.util.Computable;
import com.intellij.psi.util.MethodSignature;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.plugin2.message.JavaScriptEvalMessage;

import java.util.List;

/**
 * User: anna
 */
public class LambdaHighlightingUtil {
  @Nullable
  public static String checkInterfaceFunctional(@NotNull PsiClass psiClass) {
    return checkInterfaceFunctional(psiClass, "Target type of a lambda conversion must be an interface");
  }

  @Nullable
  public static String checkInterfaceFunctional(@NotNull PsiClass psiClass, String interfaceNonFunctionalMessage) {
    if (psiClass instanceof PsiTypeParameter) return null; //should be logged as cyclic inference
    final List<HierarchicalMethodSignature> signatures = LambdaUtil.findFunctionCandidates(psiClass);
    if (signatures == null) return interfaceNonFunctionalMessage;
    if (signatures.isEmpty()) return "No target method found";
    if (signatures.size() == 1) {
      return null;
    }
    return "Multiple non-overriding abstract methods found";
  }

  @Nullable
  public static PsiElement checkParametersCompatible(PsiLambdaExpression expression,
                                                     PsiParameter[] methodParameters,
                                                     PsiSubstitutor substitutor) {
    final PsiParameter[] lambdaParameters = expression.getParameterList().getParameters();
    if (lambdaParameters.length != methodParameters.length) {
      return expression;
    }
    else {
      boolean hasFormalParameterTypes = expression.hasFormalParameterTypes();
      for (int i = 0; i < lambdaParameters.length; i++) {
        PsiParameter lambdaParameter = lambdaParameters[i];
        PsiType lambdaParameterType = lambdaParameter.getType();
        PsiType substitutedParamType = substitutor.substitute(methodParameters[i].getType());
        if (hasFormalParameterTypes) {
          if (!PsiTypesUtil.compareTypes(lambdaParameterType, substitutedParamType, true)) {
            return lambdaParameter;
          }
        } else {
          if (!TypeConversionUtil.isAssignable(substitutedParamType, lambdaParameterType)) {
            return lambdaParameter;
          }
        }
      }
    }
    return null;
  }

  public static String checkReturnTypeCompatible(PsiLambdaExpression lambdaExpression, PsiType functionalInterfaceReturnType) {
    if (functionalInterfaceReturnType == PsiType.VOID) {
      final PsiElement body = lambdaExpression.getBody();
      if (body instanceof PsiCodeBlock) {
        if (!LambdaUtil.getReturnExpressions(lambdaExpression).isEmpty()) return "Unexpected return value";
      } else if (body instanceof PsiExpression) {
        final PsiType type = ((PsiExpression)body).getType();
        try {
          if (!PsiUtil.isStatement(JavaPsiFacade.getElementFactory(body.getProject()).createStatementFromText(body.getText(), body))) {
            return "Bad return type in lambda expression: " + (type == PsiType.NULL || type == null ? "<null>" : type.getPresentableText()) + " cannot be converted to void";
          }
        }
        catch (IncorrectOperationException ignore) {
        }
      }
    } else if (functionalInterfaceReturnType != null) {
      final List<PsiExpression> returnExpressions = LambdaUtil.getReturnExpressions(lambdaExpression);
      for (final PsiExpression expression : returnExpressions) {
        final PsiType expressionType = PsiResolveHelper.ourGraphGuard.doPreventingRecursion(expression, true, new Computable<PsiType>() {
          @Override
          public PsiType compute() {
            return expression.getType();
          }
        });
        if (expressionType != null && !functionalInterfaceReturnType.isAssignableFrom(expressionType)) {
          return "Bad return type in lambda expression: " + expressionType.getPresentableText() + " cannot be converted to " + functionalInterfaceReturnType.getPresentableText();
        }
      }
      if (LambdaUtil.getReturnStatements(lambdaExpression).length > returnExpressions.size() || returnExpressions.isEmpty() && !lambdaExpression.isVoidCompatible()) {
        return "Missing return value";
      }
    }
    return null;
  }

  public static boolean insertSemicolonAfter(PsiLambdaExpression lambdaExpression) {
     if (lambdaExpression.getBody() instanceof PsiCodeBlock) {
       return true;
     }
    if (insertSemicolon(lambdaExpression.getParent())) {
      return false;
    }
    return true;
  }

  public static boolean insertSemicolon(PsiElement parent) {
    return parent instanceof PsiExpressionList || parent instanceof PsiExpression;
  }

  @Nullable
  public static String checkInterfaceFunctional(PsiType functionalInterfaceType) {
    if (functionalInterfaceType instanceof PsiIntersectionType) {
      int count = 0;
      for (PsiType type : ((PsiIntersectionType)functionalInterfaceType).getConjuncts()) {
        if (checkInterfaceFunctional(type) == null) {
          count++;
        }
      }

      if (count > 1) {
        return "Multiple non-overriding abstract methods found in " + functionalInterfaceType.getPresentableText();
      }
      return null;
    }
    final PsiClassType.ClassResolveResult resolveResult = PsiUtil.resolveGenericsClassInType(functionalInterfaceType);
    final PsiClass aClass = resolveResult.getElement();
    if (aClass != null) {
      if (aClass instanceof PsiTypeParameter) return null; //should be logged as cyclic inference
      final List<HierarchicalMethodSignature> signatures = LambdaUtil.findFunctionCandidates(aClass);
      if (signatures != null && signatures.size() == 1) {
        final MethodSignature functionalMethod = signatures.get(0);
        if (functionalMethod.getTypeParameters().length > 0) return "Target method is generic";
      }
      if (checkReturnTypeApplicable(resolveResult, aClass)) {
        return "No instance of type " + functionalInterfaceType.getPresentableText() + " exists so that lambda expression can be type-checked";
      }
      return checkInterfaceFunctional(aClass);
    }
    return functionalInterfaceType.getPresentableText() + " is not a functional interface";
  }

  private static boolean checkReturnTypeApplicable(PsiClassType.ClassResolveResult resolveResult, final PsiClass aClass) {
    final MethodSignature methodSignature = LambdaUtil.getFunction(aClass);
    if (methodSignature == null) return false;

    for (PsiTypeParameter parameter : aClass.getTypeParameters()) {
      if (parameter.getExtendsListTypes().length == 0) continue;
      boolean depends = false;
      final PsiType substitution = resolveResult.getSubstitutor().substitute(parameter);
      if (substitution instanceof PsiWildcardType && !((PsiWildcardType)substitution).isBounded()) {
        for (PsiType paramType : methodSignature.getParameterTypes()) {
          if (LambdaUtil.depends(paramType, new LambdaUtil.TypeParamsChecker((PsiMethod)null, aClass) {
            @Override
            public boolean startedInference() {
              return true;
            }
          }, parameter)) {
            depends = true;
            break;
          }
        }
        if (!depends) return true;
      }
    }
    return false;
  }
}
