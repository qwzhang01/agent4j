package com.iwj.ancient.prose.config;

import com.iwj.ancient.prose.common.Corn;
import org.quartz.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * QuartzConfig
 *
 * @author avinzhang
 */
@Component
public class QuartzConfig implements ApplicationRunner {

    private static final String ID = "avin_quartz";

    @Autowired
    private Scheduler scheduler;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AssignableTypeFilter(QuartzJobBean.class));
        provider.addIncludeFilter(new AnnotationTypeFilter(Corn.class));
        Set<BeanDefinition> beanDefinitions = provider.findCandidateComponents("com.iwj.ancient.prose.job");

        Map<JobDetail, Set<? extends Trigger>> map = new HashMap<>();

        for (BeanDefinition definition : beanDefinitions) {
            Class<? extends QuartzJobBean> clazz = (Class<? extends QuartzJobBean>) Class.forName(definition.getBeanClassName());
            Corn corn = clazz.getAnnotation(Corn.class);

            JobDetail jobDetail = JobBuilder.newJob(clazz)
                    .withIdentity(ID + " " + definition.getBeanClassName())
                    .storeDurably()
                    .build();

            CronScheduleBuilder scheduleBuilder =
                    CronScheduleBuilder.cronSchedule(corn.value());
            Trigger trigger = TriggerBuilder.newTrigger()
                    .forJob(jobDetail)
                    .withIdentity(ID + " " + definition.getBeanClassName())
                    .withSchedule(scheduleBuilder)
                    .build();

            map.put(jobDetail, Collections.singleton(trigger));
        }

        scheduler.scheduleJobs(map, true);
    }
}
