package com.iwj.ancient.prose.config;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.Feature;
import com.googlecode.aviator.Options;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.math.MathContext;

/**
 * aviator 表达式 初始化配置
 *
 * @author avinzhang
 */
@Configuration
public class FormulaConfig {

    @PostConstruct
    public void init() {

        // AviatorEvaluator.EVAL，默认值，以运行时的性能优先，编译会花费更多时间做优化，目前会做一些常量折叠、公共变量提取的优化。适合长期运行的表达式。 表达式经常不会变化。
        // AviatorEvaluator.COMPILE 不会做任何编译优化，牺牲一定的运行性能，适合需要频繁编译表达式的场景, 比如经常编译不同的表达式。        //执行优先
        AviatorEvaluator.setOption(Options.OPTIMIZE_LEVEL, AviatorEvaluator.EVAL);
        // 编译优先  你可以修改为编译速度优先,这样不会做编译优化:
        // AviatorEvaluator.setOption(Options.OPTIMIZE_LEVEL, AviatorEvaluator.COMPILE);

        // 打开跟踪执行 默认关闭
        AviatorEvaluator.setOption(Options.TRACE_EVAL, true);

        //启用属性访问语法时，如果未找到属性值或引发异常，返回NULL  默认值为false 设置为true
        AviatorEvaluator.setOption(Options.NIL_WHEN_PROPERTY_NOT_FOUND, true);

        //计算精度 java.util.MathContext.DECIMAL128
        AviatorEvaluator.setOption(Options.MATH_CONTEXT, MathContext.DECIMAL128);

        //浮点数解析
        //是否将整型数字都解析为 BigDecimal，默认为 false，也就是不启用。在所有数字都是需要高精度计算的场景，结合 ALWAYS_PARSE_FLOATING_POINT_NUMBER_INTO_DECIMAL 选项，可以减少一些类型转换。
        AviatorEvaluator.setOption(Options.ALWAYS_PARSE_INTEGRAL_NUMBER_INTO_DECIMAL, false);

        //解析浮点数 默认 false
        //是否将所有浮点数解析为 Decimal 类型，适合需要高精度运算的场景，并且不想为每个浮点数字指定 M 后缀（表示 Decimal 类型）。默认为 false 不开启。
        AviatorEvaluator.setOption(Options.ALWAYS_PARSE_FLOATING_POINT_NUMBER_INTO_DECIMAL, false);

        /*
         * 正则分组捕获 默认为 true 开启
         * email=~/([\\w0-8]+)@\\w+[\\.\\w+]+/ ? $1:'unknow'
         * 将 email 变量中的用户名部分(@ 符号之前)匹配出来，并放到 $1变量中，如果关闭 PUT_CAPTURING_GROUPS_INTO_ENV（设置为 false），将不会将捕获的分组放入 env，也就无法获取到匹配的分组。默认为 true 开启。
         */
        AviatorEvaluator.setOption(Options.PUT_CAPTURING_GROUPS_INTO_ENV, true);

        /*
         * 变量语法糖
         * 是否启用变量访问的语法糖，默认情况下 Aviator 会通过 commons-beantuils 反射访问类似 a.b.c 这样的嵌套 JavaBean 变量，或者 #list.[0].name 这样的数组（链表）中的元素。但是部分用户可能想关闭这个行为，强制都从 env 中获取这些变量值，那么就可以将该选项关闭，也就是设置为 false。默认为 true 开启。
         */
        AviatorEvaluator.setOption(Options.ENABLE_PROPERTY_SYNTAX_SUGAR, true);

        /*
         * Env 处理
         * 从 4.0 开始，为了支持 lambda， aviator 引入了变量作用域 scope 的概念，本来的默认行为是不再修改用户传入的 env 对象，但是后面看到比较多的用户依赖这个行为，因此提供了这个新选项 USE_USER_ENV_AS_TOP_ENV_DIRECTLY，当为 true 的时候就会将用户传入的 env 作为最顶层的作用域 scope 来使用，并且默认为
         * true 启用。如果你不需要 aviator 产生副作用污染你传入的 env，这个选项更推荐设置为 false
         */
        AviatorEvaluator.setOption(Options.USE_USER_ENV_AS_TOP_ENV_DIRECTLY, true);

        /*
         * 参数捕获 默认false
         */
        AviatorEvaluator.setOption(Options.CAPTURE_FUNCTION_ARGS, false);

        /*
         * 循环次数控制
         * 限制循环语句的最大次数，这个循环包括 for 语句、 while 循环语句以及 map, filter, some 等任何涉及 sequence 遍历的高阶函数。用于限制用户传入的脚本执行循环的次数，避免死循环或者耗费大量 CPU 的场景出现。
         *
         * 默认值： 0，表示无限制。
         * 可以设置为任意正整数，比如 5000，表示单次循环最大次数是 5000。
         */
        AviatorEvaluator.setOption(Options.MAX_LOOP_COUNT, 0);

        /*
         * 语法特性 设置 AviatorScript 支持的语法特性集合，它接受的是一个 Set<Feature>  的集合
         */
        AviatorEvaluator.setOption(Options.FEATURE_SET, Feature.asSet(Feature.Assignment,
                Feature.ForLoop,
                Feature.WhileLoop,
                Feature.Lambda,
                Feature.Let));

        /*
         * 类的白名单：ALLOWED_CLASS_SET
         * 请注意：
         * ● null 表示不限制（默认值）
         * ● 空集合表示禁止任何 class
         */
        // AviatorEvaluator.setOption(Options.ALLOWED_CLASS_SET, (new HashSet<>()).add(ArrayBlockingQueue.class));


        // 设置默认缓存表达式结果
        AviatorEvaluator.getInstance().setCachedExpressionByDefault(true);
        //设置LRU缓存
        AviatorEvaluator.getInstance().useLRUExpressionCache(20000);

    }
}