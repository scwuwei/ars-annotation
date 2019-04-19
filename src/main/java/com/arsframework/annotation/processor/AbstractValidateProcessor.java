package com.arsframework.annotation.processor;

import java.util.Set;
import java.util.List;
import java.util.Iterator;
import java.lang.annotation.Annotation;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.MirroredTypesException;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;

import com.arsframework.annotation.Ignore;

/**
 * 参数校验注解处理器抽象实现
 *
 * @author yongqiang.wu
 */
public abstract class AbstractValidateProcessor extends AbstractProcessor {
    protected Names names;
    protected Context context;
    protected TreeMaker maker;
    protected JavacTrees trees;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest().compareTo(SourceVersion.RELEASE_8) > 0 ? SourceVersion.latest() : SourceVersion.RELEASE_8;
    }

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.trees = JavacTrees.instance(env);
        this.context = ((JavacProcessingEnvironment) env).getContext();
        this.names = Names.instance(this.context);
        this.maker = TreeMaker.instance(this.context);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        for (String type : this.getSupportedAnnotationTypes()) {
            try {
                Class<? extends Annotation> annotation = (Class<? extends Annotation>) Class.forName(type);
                for (Element element : env.getElementsAnnotatedWith(annotation)) {
                    if (element.getKind() == ElementKind.ENUM || element.getKind() == ElementKind.CLASS) { // 枚举/类元素
                        for (JCTree def : ((JCTree.JCClassDecl) trees.getTree(element)).defs) {
                            if (def.getKind() == Tree.Kind.METHOD) {
                                this.buildValidateBlock(((JCTree.JCMethodDecl) def).sym);
                            }
                        }
                    } else if ((element.getKind() == ElementKind.CONSTRUCTOR || element.getKind() == ElementKind.METHOD)
                            && Validates.lookupAnnotation(((Symbol) element).owner, annotation) == null) { // 方法元素
                        this.buildValidateBlock((Symbol.MethodSymbol) element);
                    } else if (element.getKind() == ElementKind.PARAMETER
                            && Validates.lookupAnnotation(((Symbol) element).owner, annotation) == null) { // 参数元素
                        this.buildValidateBlock((Symbol.VarSymbol) element);
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }

    /**
     * 判断注解是否被忽略
     *
     * @param param      参数代码对象
     * @param annotation 注解对象
     * @return true/false
     */
    protected boolean isIgnoreAnnotation(Symbol.VarSymbol param, Class<? extends Annotation> annotation) {
        Ignore ignore;
        if (annotation != Ignore.class && (ignore = Validates.lookupAnnotation(param, Ignore.class)) != null) {
            try {
                Class<? extends Annotation>[] classes = ignore.value();
                if (classes.length == 0) {
                    return true;
                }
                for (Class<? extends Annotation> cls : classes) {
                    if (cls == annotation) {
                        return true;
                    }
                }
            } catch (MirroredTypesException e) {
                List<? extends TypeMirror> mirrors = e.getTypeMirrors();
                if (mirrors.isEmpty()) {
                    return true;
                }
                String name = annotation.getCanonicalName();
                for (TypeMirror mirror : mirrors) {
                    if (mirror.toString().equals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 构建参数校验处理代码块
     *
     * @param param 参数代码对象
     */
    protected void buildValidateBlock(Symbol.VarSymbol param) {
        this.appendValidateBlock(((JCTree.JCMethodDecl) trees.getTree(param.owner)).body, param);
    }

    /**
     * 构建参数校验处理代码块
     *
     * @param method 方法代码对象
     */
    protected void buildValidateBlock(Symbol.MethodSymbol method) {
        if (method != null && method.params != null && !method.params.isEmpty()) {
            JCTree.JCBlock body = trees.getTree(method).body;
            for (Symbol.VarSymbol param : method.params) {
                this.appendValidateBlock(body, param);
            }
        }
    }

    /**
     * 添加参数校验代码块
     *
     * @param body  方法体代码对象
     * @param param 参数代码对象
     */
    private void appendValidateBlock(JCTree.JCBlock body, Symbol.VarSymbol param) {
        // 构建参数校验逻辑条件
        JCTree.JCStatement condition = this.buildValidateCondition(param);
        if (condition == null) {
            return;
        }

        // 判断方法体第一行是否为构造方法调用（super、this），如果是则将校验代码块追加到构造方法调用后面，否则添加到方法体最前面
        if (param.owner.getKind() == ElementKind.CONSTRUCTOR && body.stats.head instanceof JCTree.JCExpressionStatement) {
            JCTree.JCExpression expression = ((JCTree.JCExpressionStatement) body.stats.head).expr;
            if (expression instanceof JCTree.JCMethodInvocation
                    && ((JCTree.JCMethodInvocation) expression).meth.getKind() == Tree.Kind.IDENTIFIER) {
                ListBuffer stats = ListBuffer.of(body.stats.head).append(condition);
                Iterator<JCTree.JCStatement> iterator = body.stats.iterator();
                iterator.next(); // 过滤第一行构造方法调用
                while (iterator.hasNext()) {
                    stats.append(iterator.next());
                }
                body.stats = stats.toList(); // 重置方法体代码块
                return;
            }
        }
        body.stats = body.stats.prepend(condition); // 将参数校验条件表达式添加到方法代码块最前面
    }

    /**
     * 构建语法树参数验证条件
     *
     * @param param 参数代码对象
     * @return 验证条件表达式对象
     */
    protected abstract JCTree.JCIf buildValidateCondition(Symbol.VarSymbol param);
}
