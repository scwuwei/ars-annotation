package com.arsframework.annotation.processor;

import java.util.Arrays;

import javax.annotation.processing.SupportedAnnotationTypes;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import com.arsframework.annotation.Option;

/**
 * 参数选项校验注解处理器
 *
 * @author yongqiang.wu
 */
@SupportedAnnotationTypes("com.arsframework.annotation.Option")
public class OptionValidateProcessor extends AbstractValidateProcessor {

    @Override
    protected JCTree.JCIf buildValidateCondition(Symbol.VarSymbol param) {
        Option option = Validates.lookupAnnotation(param, Option.class);
        JCTree.JCExpression expression = Validates.buildOptionExpression(maker, names, param, option.value());
        return expression == null ? null : maker.If(expression,
                maker.Throw(Validates.buildExceptionExpression(maker, names, option.exception(),
                        String.format(option.message(), param.name.toString(), Arrays.toString(option.value())))), null);
    }
}
