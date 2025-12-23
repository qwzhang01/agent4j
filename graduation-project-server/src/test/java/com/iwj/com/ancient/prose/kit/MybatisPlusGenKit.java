package com.iwj.com.ancient.prose.kit;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.baomidou.mybatisplus.core.toolkit.StringPool;
import com.baomidou.mybatisplus.generator.AutoGenerator;
import com.baomidou.mybatisplus.generator.InjectionConfig;
import com.baomidou.mybatisplus.generator.config.*;
import com.baomidou.mybatisplus.generator.config.po.TableFill;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import com.baomidou.mybatisplus.generator.config.rules.NamingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.*;

@Slf4j
public class MybatisPlusGenKit {

    private final Properties prop;

    /**
     * 获取配置信息
     *
     * @return
     */
    public MybatisPlusGenKit() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        this.prop = yaml.getObject();
    }

    /**
     * 读取控制台内容
     */
    private String scanner(String tip) {

        if (StringUtils.isNotBlank(prop.getProperty("tables"))) {
            System.out.println("--------------------------------------------------------");
            System.out.println("--------------------------------------------------------");
            System.out.println("表：");
            System.out.println(prop.getProperty("tables"));
            System.out.println("--------------------------------------------------------");
            System.out.println("--------------------------------------------------------");
        }

        Scanner scanner = new Scanner(System.in);
        log.info("请输入" + tip + "：");
        if (scanner.hasNext()) {
            String ipt = scanner.next();
            if (StringUtils.isNotBlank(ipt)) {
                return ipt.trim();
            }
        }
        throw new MybatisPlusException("请输入正确的" + tip + "！");
    }

    @Test
    public void gen() {
        final String projectPath = System.getProperty("user.dir");
        // 代码生成器
        AutoGenerator mpg = new AutoGenerator();

        // 全局配置
        GlobalConfig gc = new GlobalConfig();
        gc.setOutputDir(projectPath + "/src/test/java");
        gc.setAuthor("avinzhang");
        gc.setOpen(false);
        gc.setFileOverride(true);
        gc.setEnableCache(false);
        // 实体自带save、update方法
        gc.setActiveRecord(true);

        // 自定义文件命名，注意 %s 会自动填充表实体属性！
        gc.setControllerName("%sController");
        // 默认service接口名IXXXService 自定义指定之后就不会用I开头了
        gc.setServiceName("%sService");
        gc.setServiceImplName("%sServiceImpl");
        gc.setMapperName("%sMapper");
        gc.setXmlName("%sMapper");
        // 使用 雪花算法 做主键
        gc.setIdType(IdType.ASSIGN_ID);
        gc.setSwagger2(false); // 实体属性 Swagger2 注解
        mpg.setGlobalConfig(gc);

        // 数据源配置
        DataSourceConfig dsc = new DataSourceConfig();
        dsc.setDbType(DbType.MYSQL);
        dsc.setUrl(prop.getProperty("spring.datasource.url"));
        // dsc.setSchemaName("public");
        dsc.setDriverName(prop.getProperty("spring.datasource.driver-class-name"));

        dsc.setUsername(prop.getProperty("spring.datasource.username"));

        byte[] pwd = Base64.getDecoder().decode(prop.getProperty("spring.datasource.password"));
        dsc.setPassword(new String(pwd));

        mpg.setDataSource(dsc);

        // 包配置
        final PackageConfig pc = new PackageConfig();

        String modelName = scanner("模块名");
        if (modelName.equals("none")) {
            pc.setModuleName("");
        } else {
            pc.setModuleName(modelName.trim());
        }
        pc.setParent(prop.getProperty("spring.datasource.packages"));
//        pc.setMapper("dao");
//        pc.setEntity("model");
        pc.setMapper("mapper");
        pc.setEntity("entity");
        mpg.setPackageInfo(pc);

        // 自定义配置
        InjectionConfig cfg = new InjectionConfig() {
            @Override
            public void initMap() {
                // to do nothing
            }
        };

        // 如果模板引擎是 velocity
        String templatePath = "/templates/mapper.xml.vm";

        // 自定义输出配置
        List<FileOutConfig> focList = new ArrayList<>();
        // 自定义配置会被优先输出
        focList.add(new FileOutConfig(templatePath) {
            @Override
            public String outputFile(TableInfo tableInfo) {
                // 自定义输出文件名 ， 如果你 Entity 设置了前后缀、此处注意 xml 的名称会跟着发生变化！！
                return projectPath + "/src/test/resources/mapper/" + pc.getModuleName()
                        + "/" + tableInfo.getEntityName() + "Mapper" + StringPool.DOT_XML;
            }
        });

        /*
        cfg.setFileCreate(new IFileCreate() {
            @Override
            public boolean isCreate(ConfigBuilder configBuilder, FileType fileType, String filePath) {
                // 判断自定义文件夹是否需要创建
                checkDir("调用默认方法创建的目录，自定义目录用");
                if (fileType == FileType.MAPPER) {
                    // 已经生成 mapper 文件判断存在，不想重新生成返回 false
                    return !new File(filePath).exists();
                }
                // 允许生成模板文件
                return true;
            }
        });
        */
        cfg.setFileOutConfigList(focList);
        mpg.setCfg(cfg);

        // 配置模板
        TemplateConfig templateConfig = new TemplateConfig();

        // 配置自定义输出模板
        //指定自定义模板路径，注意不要带上.ftl/.vm, 会根据使用的模板引擎自动识别
        templateConfig.setEntity("entity.java");
        // templateConfig.setService();
        // templateConfig.setController();
        templateConfig.setMapper("mapper.java");
        templateConfig.setXml(null);
        mpg.setTemplate(templateConfig);

        // 策略配置
        StrategyConfig strategy = new StrategyConfig();
        strategy.setNaming(NamingStrategy.underline_to_camel);
//        strategy.setColumnNaming(NamingStrategy.underline_to_camel);
         strategy.setColumnNaming(NamingStrategy.no_change);
        // strategy.setSuperEntityClass("你自己的父类实体,没有就不用设置!");
        strategy.setEntityLombokModel(true);
        strategy.setChainModel(true);
        strategy.setRestControllerStyle(true);
        strategy.setEntitySerialVersionUID(true);
        strategy.setControllerMappingHyphenStyle(true);
        // 公共父类
        // strategy.setSuperControllerClass("你自己的父类控制器,没有就不用设置!");
        // 写于父类中的公共字段
        // strategy.setSuperEntityColumns("id");
        String[] tableNames = scanner("表名，多个英文逗号分割").split(",");
        for (int i = 0; i < tableNames.length; i++) {
            tableNames[i] = tableNames[i].trim();
        }
        strategy.setInclude(tableNames);
        strategy.setTablePrefix(pc.getModuleName() + "_");

        String prefix = prop.getProperty("spring.datasource.prefix");
        if (StringUtils.isNotBlank(prefix)) {
            strategy.setTablePrefix(prefix.split("\\,"));
        } else {
            strategy.setTablePrefix("eb");
        }

        strategy.setLogicDeleteFieldName("enableFlag");
        // 在createTime createBy updateTime updateBy 添加注解，给起添加默认值
        strategy.setEntityTableFieldAnnotationEnable(true);
        List<TableFill> list = new ArrayList<>();
        TableFill createTime = new TableFill("createTime", FieldFill.INSERT);
        list.add(createTime);
        TableFill createBy = new TableFill("createBy", FieldFill.INSERT);
        list.add(createBy);
        TableFill updateTime = new TableFill("updateTime", FieldFill.INSERT_UPDATE);
        list.add(updateTime);
        TableFill updateBy = new TableFill("updateBy", FieldFill.INSERT_UPDATE);
        list.add(updateBy);
        TableFill enableFlag = new TableFill("enableFlag", FieldFill.INSERT);
        list.add(enableFlag);
        TableFill corpKey = new TableFill("corpKey", FieldFill.INSERT);
        list.add(corpKey);
        strategy.setTableFillList(list);

        mpg.setStrategy(strategy);
        mpg.execute();

        assert true;
    }
}

