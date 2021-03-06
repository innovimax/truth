/*
 * Copyright (C) 2010 Google, Inc.
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
package com.google.common.truth.codegen;

import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isPrivate;
import static java.lang.reflect.Modifier.isStatic;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.base.Joiner;
import com.google.common.truth.ReflectionUtil;
import com.google.common.truth.Subject;
import com.google.common.truth.SubjectFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/**
 * A builder of classes to wrap a provided SubjectFactory's concrete Subject subclass.
 * The generated class will be a direct subclass of the concrete Subject subclass, but
 * each public, protected, or friendly method not declared by Object will be wrapped
 * such that invocations on it will be invoked on a new Subject instance populated
 * with an element in the provided collection.  This allows for a type-safe, IDE-discoverable
 * Subject in a for-each style.
 */
@GwtIncompatible("java.lang.reflect.*")
public class IteratingWrapperClassBuilder {
  private static final Joiner NEW_LINE_JOINER = Joiner.on("%n");

  /**
   * <p>A string intended for use in String.format() representing the
   *    text of the code of the wrapper class.
   *
   * <p>Format parameters include:
   * <ol>
   *   <li>package name</li>
   *   <li>simple name of a concrete subtype of Subject</li>
   *   <li>the fully qualified name of the target type</li>
   *   <li>the text of the code of the wrapped methods</li>
   * </ol>
   * </p>
   */
  private static final String CLASS_TEMPLATE =
      NEW_LINE_JOINER.join(
          "package %1$s;",
          "",
          "import com.google.common.truth.FailureStrategy;",
          "import com.google.common.truth.SubjectFactory;",
          "",
          "public class %2$sIteratingWrapper extends %2$s {",
          "",
          "  private final SubjectFactory subjectFactory;",
          "  private final Iterable<%3$s> data;",
          "",
          "  public %2$sIteratingWrapper(",
          "      FailureStrategy failureStrategy,",
          "      SubjectFactory<?, ?> subjectFactory,",
          "      Iterable<%3$s> data",
          "  ) {",
          "    super(failureStrategy, (%3$s)null);",
          "    this.subjectFactory = subjectFactory;",
          "    this.data = data;",
          "  }",
          "",
          "%4$s",
          "}");

  /**
   * <p>A string intended for use in String.format() representing the
   *    text of the code of all wrapped methods.
   *
   * <p>Format parameters include:
   * <ol>
   *   <li>visibility</li>
   *   <li>fully qualified name of the return type</li>
   *   <li>method name</li>
   *   <li>method's parameter list</li>
   *   <li>the target type of the Subject</li>
   *   <li>concrete subtype of Subject to be wrapped</li>
   *   <li>parameter list</li>
   * </ol>
   * </p>
   */
  private static final String WRAPPER_METHOD_TEMPLATE =
      NEW_LINE_JOINER.join(
          "  @Override %1$s %2$s %3$s(%4$s) {",
          "    for (%5$s item : data) {",
          "      %6$s subject = (%6$s)subjectFactory.getSubject(failureStrategy, item);",
          "      subject.%3$s(%7$s);",
          "    }",
          "  }");

  private static final int TARGET_TYPE_PARAMETER = 1;

  private static final String ITERATING_WRAPPER = "IteratingWrapper";

  private final SubjectFactory<?, ?> subjectFactory;

  public final String className;

  public IteratingWrapperClassBuilder(SubjectFactory<?, ?> subjectFactory) {
    this.subjectFactory = subjectFactory;
    this.className = subjectFactory.getSubjectClass().getCanonicalName() + ITERATING_WRAPPER;
  }

  public String build() {
    Class<?> subjectClass = subjectFactory.getSubjectClass();
    List<Method> methods = Arrays.asList(subjectClass.getMethods());
    Class<?> targetType = ReflectionUtil.typeParameter(subjectClass, TARGET_TYPE_PARAMETER);

    StringBuilder methodWrappers = new StringBuilder();
    for (Method m : methods) {
      appendMethodWrapper(methodWrappers, subjectClass, targetType, m);
    }
    String code =
        String.format(
            CLASS_TEMPLATE,
            subjectClass.getPackage().getName(),
            subjectClass.getSimpleName(),
            targetType.getCanonicalName(),
            methodWrappers.toString());

    return code;
  }

  private void appendMethodWrapper(
      StringBuilder code, Class<?> subjectType, Class<?> targetType, Method method) {
    int modifiers = method.getModifiers();
    boolean shouldWrap =
        (method.getDeclaringClass() != Subject.class)
            && !method.getDeclaringClass().equals(Object.class)
            && !(isFinal(modifiers) || isPrivate(modifiers) || isStatic(modifiers));

    if (shouldWrap) {
      code.append(
          String.format(
              WRAPPER_METHOD_TEMPLATE,
              stringVisibility(modifiers),
              method.getReturnType().getCanonicalName(),
              method.getName(),
              methodSignature(method.getParameterTypes(), method.getParameterAnnotations()),
              targetType.getCanonicalName(),
              subjectType.getCanonicalName(),
              methodParameterList(method.getParameterTypes().length)));
    }
  }

  private static StringBuilder methodParameterList(int length) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < length; i++) {
      if (i > 0) builder.append(", ");
      builder.append("arg").append(i);
    }
    return builder;
  }

  /** Builds a string for the parameters within a method signature. */
  private static StringBuilder methodSignature(Class<?>[] parameters, Annotation[][] annotations) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0, iLen = parameters.length; i < iLen; i++) {
      if (i > 0) builder.append(", ");
      for (int j = 0, jLen = annotations[i].length; j < jLen; j++) {
        if (j > 0) builder.append(" ");
        builder.append("@").append(annotations[i][j].annotationType().getCanonicalName());
        builder.append(" ");
      }
      builder.append(parameters[i].getCanonicalName());
      builder.append(" arg").append(i);
    }
    return builder;
  }

  private static String stringVisibility(int modifiers) {
    if (Modifier.isProtected(modifiers)) {
      return "protected";
    } else if (Modifier.isPublic(modifiers)) {
      return "public";
    } else {
      return "";
    }
  }
}
