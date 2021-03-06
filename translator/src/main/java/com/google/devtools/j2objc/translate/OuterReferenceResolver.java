/*
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

package com.google.devtools.j2objc.translate;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.devtools.j2objc.ast.AnnotationTypeDeclaration;
import com.google.devtools.j2objc.ast.AnonymousClassDeclaration;
import com.google.devtools.j2objc.ast.ClassInstanceCreation;
import com.google.devtools.j2objc.ast.EnumDeclaration;
import com.google.devtools.j2objc.ast.FieldAccess;
import com.google.devtools.j2objc.ast.LambdaExpression;
import com.google.devtools.j2objc.ast.MethodDeclaration;
import com.google.devtools.j2objc.ast.MethodInvocation;
import com.google.devtools.j2objc.ast.Name;
import com.google.devtools.j2objc.ast.QualifiedName;
import com.google.devtools.j2objc.ast.SimpleName;
import com.google.devtools.j2objc.ast.SingleVariableDeclaration;
import com.google.devtools.j2objc.ast.SuperMethodInvocation;
import com.google.devtools.j2objc.ast.ThisExpression;
import com.google.devtools.j2objc.ast.TreeNode;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.TreeVisitor;
import com.google.devtools.j2objc.ast.TypeDeclaration;
import com.google.devtools.j2objc.ast.VariableDeclaration;
import com.google.devtools.j2objc.ast.VariableDeclarationFragment;
import com.google.devtools.j2objc.types.GeneratedVariableBinding;
import com.google.devtools.j2objc.util.BindingUtil;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Visits a compilation unit and creates variable bindings for outer references
 * and captured local variables where required. Also generates an outer
 * reference path for any nodes where an outer reference is required. The
 * generated paths are lists of variable bindings for the outer fields that can
 * be used to reconstruct the given expression.
 *
 * OuterReferenceResolver should be run prior to any AST mutations.
 *
 * @author Keith Stanger
 */
public class OuterReferenceResolver extends TreeVisitor {

  // A placeholder variable binding that should be replaced with the outer
  // parameter in a constructor.
  public static final IVariableBinding OUTER_PARAMETER = GeneratedVariableBinding.newPlaceholder();

  private Map<ITypeBinding, IVariableBinding> outerVars = new HashMap<>();
  private Set<ITypeBinding> usesOuterParam = new HashSet<>();
  private Set<ITypeBinding> hasImplicitCaptures = new HashSet<>();
  private ListMultimap<ITypeBinding, Capture> captures = ArrayListMultimap.create();
  private Map<TreeNode.Key, List<IVariableBinding>> outerPaths = new HashMap<>();
  private ArrayList<Scope> scopeStack = new ArrayList<>();

  @Override
  public void run(TreeNode node) {
    assert scopeStack.isEmpty();
    super.run(node);
  }

  public boolean needsOuterReference(ITypeBinding type) {
    return outerVars.containsKey(type);
  }

  public boolean needsOuterParam(ITypeBinding type) {
    return !type.isLocal() || outerVars.containsKey(type) || usesOuterParam.contains(type);
  }

  public boolean hasImplicitCaptures(ITypeBinding type) {
    return BindingUtil.isLambda(type) && hasImplicitCaptures.contains(type);
  }

  public IVariableBinding getOuterField(ITypeBinding type) {
    return outerVars.get(type);
  }

  public List<IVariableBinding> getCapturedVars(ITypeBinding type) {
    List<Capture> capturesForType = captures.get(type);
    List<IVariableBinding> capturedVars = new ArrayList<>(capturesForType.size());
    for (Capture capture : capturesForType) {
      capturedVars.add(capture.var);
    }
    return capturedVars;
  }

  public List<IVariableBinding> getInnerFields(ITypeBinding type) {
    List<Capture> capturesForType = captures.get(type);
    List<IVariableBinding> innerFields = new ArrayList<>(capturesForType.size());
    for (Capture capture : capturesForType) {
      innerFields.add(capture.field);
    }
    return innerFields;
  }

  public List<IVariableBinding> getPath(TreeNode node) {
    return outerPaths.get(node.getKey());
  }

  private static class Capture {

    private final IVariableBinding var;
    private final IVariableBinding field;

    private Capture(IVariableBinding var, IVariableBinding field) {
      this.var = var;
      this.field = field;
    }
  }

  private static class Scope {

    private final ITypeBinding type;
    private final Set<ITypeBinding> inheritedScope;
    private boolean initializingContext = true;
    private Set<IVariableBinding> declaredVars = new HashSet<>();

    private Scope(ITypeBinding type) {
      this.type = type;
      ImmutableSet.Builder<ITypeBinding> inheritedScopeBuilder = ImmutableSet.builder();
      for (ITypeBinding inheritedType : BindingUtil.getInheritedTypesInclusive(type)) {
        inheritedScopeBuilder.add(inheritedType.getTypeDeclaration());
      }
      this.inheritedScope = inheritedScopeBuilder.build();
    }
  }

  private Scope peekScope() {
    assert scopeStack.size() > 0;
    return scopeStack.get(scopeStack.size() - 1);
  }

  private String getOuterFieldName(ITypeBinding type) {
    // Ensure that the new outer field does not conflict with a field in a superclass.
    type = type.getSuperclass();
    int suffix = 0;
    while (type != null) {
      if (BindingUtil.hasOuterContext(type)) {
        suffix++;
      }
      type = type.getSuperclass();
    }
    return "this$" + suffix;
  }

  private IVariableBinding getOrCreateOuterField(Scope scope) {
    assert !BindingUtil.isLambda(scope.type);

    if (scope.initializingContext && scope == peekScope()) {
      usesOuterParam.add(scope.type);
      return OUTER_PARAMETER;
    }
    ITypeBinding type = scope.type;
    IVariableBinding outerField = outerVars.get(type);
    if (outerField == null) {
      outerField = new GeneratedVariableBinding(getOuterFieldName(type),
          Modifier.PRIVATE | Modifier.FINAL, type.getDeclaringClass(), true, false, type, null);
      outerVars.put(type, outerField);
    }
    return outerField;
  }

  private IVariableBinding getOrCreateInnerField(IVariableBinding var, ITypeBinding declaringType) {
    assert !BindingUtil.isLambda(declaringType);

    List<Capture> capturesForType = captures.get(declaringType);
    IVariableBinding innerField = null;
    for (Capture capture : capturesForType) {
      if (var.equals(capture.var)) {
        innerField = capture.field;
        break;
      }
    }
    if (innerField == null) {
      GeneratedVariableBinding newField = new GeneratedVariableBinding("val$" + var.getName(),
          Modifier.PRIVATE | Modifier.FINAL, var.getType(), true, false, declaringType, null);
      newField.addAnnotations(var);
      innerField = newField;
      captures.put(declaringType, new Capture(var, innerField));
    }
    return innerField;
  }

  /**
   * Mark all lambda scopes in the scope stack as capturing. If a lambda is capturing, its
   * enclosing lambdas must also be capturing.
   */
  private void markImplicitCaptures() {
    for (int i = scopeStack.size() - 1; i >= 0; i--) {
      Scope scope = scopeStack.get(i);
      if (BindingUtil.isLambda(scope.type)) {
        hasImplicitCaptures.add(scope.type);
      }
    }
  }

  private List<IVariableBinding> getOuterPath(ITypeBinding type) {
    type = type.getTypeDeclaration();
    List<IVariableBinding> path = new ArrayList<>();
    for (int i = scopeStack.size() - 1; i >= 0; i--) {
      Scope scope = scopeStack.get(i);
      if (type.equals(scope.type)) {
        break;
      }
      if (!BindingUtil.isLambda(scope.type)) {
        path.add(getOrCreateOuterField(scope));
      } else {
        hasImplicitCaptures.add(scope.type);
      }
    }
    return path;
  }

  private List<IVariableBinding> getOuterPathInherited(ITypeBinding type) {
    type = type.getTypeDeclaration();
    List<IVariableBinding> path = new ArrayList<>();
    for (int i = scopeStack.size() - 1; i >= 0; i--) {
      Scope scope = scopeStack.get(i);
      if (scope.inheritedScope.contains(type)) {
        break;
      }
      if (!BindingUtil.isLambda(scope.type)) {
        path.add(getOrCreateOuterField(scope));
      } else {
        hasImplicitCaptures.add(scope.type);
      }
    }
    return path;
  }

  private List<IVariableBinding> getPathForField(IVariableBinding var) {
    List<IVariableBinding> path = getOuterPathInherited(var.getDeclaringClass());
    if (!path.isEmpty()) {
      path.add(var);
    }
    return path;
  }

  private List<IVariableBinding> getPathForLocalVar(IVariableBinding var) {
    boolean isConstant = var.getConstantValue() != null;
    ArrayList<IVariableBinding> path = new ArrayList<>();

    int lastScopeIdx = -1;
    int lastNonLambdaScopeIdx = -1;
    int scopeStackSize = scopeStack.size();

    for (int i = scopeStackSize - 1; i >= 0; i--) {
      Scope scope = scopeStack.get(i);
      if (scope.declaredVars.contains(var)) {
        break;
      }

      lastScopeIdx = i;
      if (!BindingUtil.isLambda(scope.type)) {
        lastNonLambdaScopeIdx = i;
      }
    }

    if (lastScopeIdx == -1) {
      // Var must already be in the declaring scope. Return an empty path.
      return path;
    }

    // Constant reference only needs a one-element path.
    if (isConstant) {
      path.add(var);
      return path;
    }

    // Traverse from the top of the stack, if the scope is lambda, mark it as a capturing lambda. If
    // the scope is the last anonymous (or inner/local) class, get/create an inner field and add
    // that to the path. Otherwise, get/create an outer field reference and add that to the path.
    // This arrangement ensures that lambdas are always using the variable references closest to
    // the class scope that encloses it.
    for (int i = scopeStackSize - 1; i >= lastScopeIdx; i--) {
      Scope scope = scopeStack.get(i);
      if (i == lastNonLambdaScopeIdx) {
        path.add(getOrCreateInnerField(var, scope.type));
      } else {
        if (BindingUtil.isLambda(scope.type)) {
          hasImplicitCaptures.add(scope.type);
        } else {
          path.add(getOrCreateOuterField(scope));
        }
      }
    }

    return path;
  }

  private void addPath(TreeNode node, List<IVariableBinding> path) {
    if (!path.isEmpty()) {
      outerPaths.put(node.getKey(), path);
    }
  }

  private void pushType(TreeNode node, ITypeBinding type) {
    scopeStack.add(new Scope(type));

    ITypeBinding superclass = type.getSuperclass();
    if (superclass != null && BindingUtil.hasOuterContext(superclass)) {
      addPath(node, getOuterPathInherited(superclass.getDeclaringClass()));
    }
  }

  private void popType() {
    scopeStack.remove(scopeStack.size() - 1);
  }

  @Override
  public boolean visit(TypeDeclaration node) {
    pushType(node, node.getTypeBinding());
    return true;
  }

  @Override
  public void endVisit(TypeDeclaration node) {
    popType();
  }

  @Override
  public boolean visit(AnonymousClassDeclaration node) {
    pushType(node, node.getTypeBinding());
    return true;
  }

  @Override
  public void endVisit(AnonymousClassDeclaration node) {
    popType();
  }

  @Override
  public boolean visit(EnumDeclaration node) {
    pushType(node, node.getTypeBinding());
    return true;
  }

  @Override
  public void endVisit(EnumDeclaration node) {
    popType();
  }

  @Override
  public boolean visit(AnnotationTypeDeclaration node) {
    pushType(node, node.getTypeBinding());
    return true;
  }

  @Override
  public void endVisit(AnnotationTypeDeclaration node) {
    popType();
  }

  @Override
  public boolean visit(LambdaExpression node) {
    pushType(node, node.getTypeBinding());
    return true;
  }

  @Override
  public void endVisit(LambdaExpression node) {
    popType();
  }

  @Override
  public boolean visit(FieldAccess node) {
    node.getExpression().accept(this);
    return false;
  }

  @Override
  public boolean visit(QualifiedName node) {
    node.getQualifier().accept(this);
    return false;
  }

  @Override
  public boolean visit(SimpleName node) {
    IVariableBinding var = TreeUtil.getVariableBinding(node);
    if (var != null) {
      if (var.isField() && !Modifier.isStatic(var.getModifiers())) {
        addPath(node, getPathForField(var));
      } else if (!var.isField()) {
        addPath(node, getPathForLocalVar(var));
      }
    }
    return true;
  }

  @Override
  public boolean visit(ThisExpression node) {
    Name qualifier = node.getQualifier();
    if (qualifier != null) {
      addPath(node, getOuterPath(qualifier.getTypeBinding()));
    } else {
      markImplicitCaptures();
    }
    return true;
  }

  @Override
  public void endVisit(MethodInvocation node) {
    IMethodBinding method = node.getMethodBinding();
    if (node.getExpression() == null && !Modifier.isStatic(method.getModifiers())) {
      addPath(node, getOuterPathInherited(method.getDeclaringClass()));
    }
  }

  @Override
  public void endVisit(SuperMethodInvocation node) {
    Name qualifier = node.getQualifier();
    if (qualifier != null) {
      addPath(node, getOuterPath(qualifier.getTypeBinding()));
    } else {
      markImplicitCaptures();
    }
  }

  @Override
  public void endVisit(ClassInstanceCreation node) {
    ITypeBinding type = node.getTypeBinding();
    if (node.getExpression() == null && BindingUtil.hasOuterContext(type)) {
      addPath(node, getOuterPathInherited(type.getDeclaringClass()));
    }
  }

  private boolean visitVariableDeclaration(VariableDeclaration node) {
    assert scopeStack.size() > 0;
    Scope currentScope = scopeStack.get(scopeStack.size() - 1);
    currentScope.declaredVars.add(node.getVariableBinding());
    return true;
  }

  @Override
  public boolean visit(VariableDeclarationFragment node) {
    return visitVariableDeclaration(node);
  }

  @Override
  public boolean visit(SingleVariableDeclaration node) {
    return visitVariableDeclaration(node);
  }

  @Override
  public boolean visit(MethodDeclaration node) {
    IMethodBinding binding = node.getMethodBinding();
    // Assume all code except for non-constructor methods is initializer code.
    if (!binding.isConstructor()) {
      peekScope().initializingContext = false;
    }
    return true;
  }

  @Override
  public void endVisit(MethodDeclaration node) {
    IMethodBinding binding = node.getMethodBinding();
    if (!binding.isConstructor()) {
      peekScope().initializingContext = true;
    }
  }
}
