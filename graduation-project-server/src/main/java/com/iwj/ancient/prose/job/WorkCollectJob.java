package com.iwj.ancient.prose.job;

import com.iwj.ancient.prose.common.Corn;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * 定期将缓存作业收集到文件
 *
 * @author avinzhang
 */
@Slf4j
@Corn("0 0/1 * * * ? *")
@AllArgsConstructor
public class WorkCollectJob extends QuartzJobBean {

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        log.info("---{}", context.toString());
    }
}