package cn.ac.iie.migrate;

import cn.ac.iie.cassandra.CassandraUtils;
import cn.ac.iie.drive.Options;
import com.google.common.collect.Lists;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static cn.ac.iie.utils.FileUtils.*;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

/**
 * 文件迁移工具类
 *
 * @author Xiang
 * @date 2016-09-20 16:58
 */
public class MigrateUtils {

    private static final Logger LOG = LoggerFactory.getLogger(MigrateUtils.class);
    private static final String TMP_SUFFIX = ".tmp";
    private static final String DATE_FORMAT_STR = "yyyy-MM-dd HH:mm:ss";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat(DATE_FORMAT_STR);
    private static final String DO_MIGRATE_JOB = "do.migrate.job";
    private static final String MAIN_MIGRATE_JOB = "main.migrate.job";
    private static final String MIGRATE_JOB = "migrate.job";
    private static final String MIGRATE_JOB_SUFFIX = ".migrate.job";
    private static final String MIGRATE_TRIGGER = "migrate.trigger";
    private static final String MIGRATE_TRIGGER_SUFFIX = ".migrate.trigger";
    private static final String DO_MIGRATE_TRIGGER = "do.migrate.trigger";
    private static final String MAIN_MIGRATE_TRIGGER = "main.migrate.trigger";
    private static Scheduler scheduler;

    /**
     * 设置冷数据迁移任务，并开始执行
     */
    public static void startMigrate(){
        LOG.info("设置定时冷数据迁移任务，执行时间：{}", Options.instance.cronExpression);
        int diskCount = Options.instance.migrateDirectories.dirCount();
        try {
            Properties p = new Properties();
            p.setProperty("org.quartz.threadPool.threadCount", ++diskCount+"");
            p.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
            SchedulerFactory factory = new StdSchedulerFactory(p);
            scheduler = factory.getScheduler();

            JobDetail job = newJob(MigrateTask.class)
                    .withIdentity(MAIN_MIGRATE_JOB, MIGRATE_JOB)
                    .withDescription("冷数据迁移任务")
                    .build();
            CronTrigger trigger = newTrigger()
                    .withIdentity(MAIN_MIGRATE_TRIGGER, MIGRATE_TRIGGER)
                    .withSchedule(CronScheduleBuilder.cronSchedule(Options.instance.cronExpression))
                    .build();
            Date startTime = scheduler.scheduleJob(job, trigger);
            LOG.info("迁移任务即将在 {} 开始执行", DATE_FORMAT.format(startTime));
            scheduler.start();
            while (true){
                if(scheduler.getTriggerState(
                        new TriggerKey(MAIN_MIGRATE_TRIGGER,
                                MIGRATE_TRIGGER))
                        == Trigger.TriggerState.COMPLETE){
                    break;
                }
                Thread.sleep(5000);
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            LOG.error("冷数据迁移异常退出");
            System.exit(-1);
        }
    }

    /**
     * 执行冷数据迁移<br/>
     * 首先获取相关表的所有待迁移sstable文件<br/>
     * 然后对各个sstable文件创建迁移任务并开始执行
     */
    public static void startDoMigrateTask(){
        try {
            int existsTaskCount = scheduler.getJobKeys(GroupMatcher.groupContains(DO_MIGRATE_JOB)).size();
            if(existsTaskCount > 0){
                LOG.warn("当前还有{}个任务正在运行， 下次重试", existsTaskCount);
                return;
            }
            List<File> files = CassandraUtils.expiredSSTableFromName(Options.instance.ksName,
                    Options.instance.tbName,
                    Options.instance.expiredSecond);

            // 对每个文件创建迁移任务，并在5秒钟后开始执行
            files.forEach(file -> startDoMigrateTask(file, 0, 5));

        } catch (Exception e) {
            LOG.error(e.getMessage(), e);

            LOG.error("冷数据迁移异常退出");
            System.exit(-1);
        }
    }

    private static void startDoMigrateTask(File ssTable, final int attempt, int delaySecond){
        Date startTime;
        if(delaySecond < 60){
            startTime = DateBuilder.nextGivenSecondDate(null, delaySecond > 0? delaySecond:5);
        } else{
            startTime = DateBuilder.nextGivenMinuteDate(null, delaySecond / 60);
        }
        String jobName = ssTable.getName()+".NO."+attempt;
        JobDetail job = newJob(DoMigrateTask.class)
                .withIdentity(jobName + MIGRATE_JOB_SUFFIX, DO_MIGRATE_JOB)
                .withDescription("冷数据迁移任务")
                .build();
        job.getJobDataMap().put("sstable", ssTable);
        job.getJobDataMap().put("attempt", attempt);
        SimpleTrigger trigger = (SimpleTrigger) newTrigger()
                .withIdentity(jobName + MIGRATE_TRIGGER_SUFFIX, DO_MIGRATE_TRIGGER)
                .startAt(startTime)
                .build();

        try {
            startTime = scheduler.scheduleJob(job, trigger);
            LOG.info("对sstable（{}）的迁移任务即将在 {} 开始第{}次执行",
                    ssTable.getName(),
                    DATE_FORMAT.format(startTime),
                    attempt+1);
        } catch (SchedulerException e) {
            LOG.error(e.getMessage(), e);
            LOG.error("冷数据迁移异常退出");
            System.exit(-1);
        }
    }

    /**
     * 对sstable文件进行迁移
     * @param file sstable文件
     */
    public static boolean doMigrate(File file, final int attempt){
        List<File> failedFiles = Lists.newArrayList();
        String fileName = file.getName();
//                String parentPath = file.getParentFile().getAbsolutePath();
        MigrateDirectory directory = null;
        boolean migrated;
        try {
            // 从待选迁移目标目录中选择一个剩余空间最大的目录作为迁移目标目录
            directory = Options.instance.migrateDirectories.poll(0, 10);
        } catch (InterruptedException e) {
            LOG.error(e.getMessage(), e);
            LOG.error("冷数据迁移异常退出");
//                System.exit(-1);
        }
        if(directory == null){
            migrated = false;
        } else {
            String tableDir = file.getParentFile().getName();
            String ksDir = file.getParentFile().getParentFile().getName();
            String targetDir = directory.getAbsolutePath() + File.separator +
                    ksDir + File.separator + tableDir;
            if(createDirIfNotExists(new File(targetDir))) {
                File targetFile = new File(targetDir + File.separator + fileName);

                migrated = doMigrate(file, targetFile);
            } else{
                migrated = false;
            }
            // 重新将迁移目录放回,
            // 先取出再放回的原因是迁移过后数据目录磁盘空间会发生变化，
            // 因此该迁移目录的排序位置可能发生变化
            Options.instance.migrateDirectories.add(directory);
        }
        // 若有文件迁移失败，则重新对文件进行迁移，
        // 新的任务将在5分钟以后执行
        // 若尝试次数超过最大尝试次数，则不再进行重试
        if(!migrated){
            if(attempt >= Options.instance.maxMigrateAttemptTimes){
                LOG.error("迁移尝试次数超过最大尝试次数: {}， 请检查磁盘并尝试重新开始迁移",
                        Options.instance.maxMigrateAttemptTimes);
                LOG.error("请检查以下未迁移成功sstable： {}", failedFiles.toString());
            } else {
                startDoMigrateTask(file, attempt + 1, 300);
            }
        } else{
            CassandraUtils.removeMaxTimestamp(file.getAbsolutePath());
        }
        return migrated;
    }

    /**
     * 迁移文件并在源文件位置创建软连接<br/>
     * 迁移过程如下：<br/>
     * 首先将原sstable SS文件复制到目标目录，路径为DS，若复制失败则迁移失败<br/>
     * 然后将原sstable SS文件重命名为SS.tmp，若重命名失败则删除DS，迁移失败；<br/>
     * 然后在原sstable SS文件创建软连接LS 指向目录D下的目标文件DS，若创建失败则还原SS.tmp并删除DS，迁移失败。
     *
     * @param sourceFile 原sstable文件
     * @param targetFile 目标sstable文件
     */
    public static boolean doMigrate(File sourceFile, File targetFile){
        String sourceAbsolutePath = sourceFile.getAbsolutePath();
        File tmpFile = new File(sourceFile.getParentFile(), sourceFile.getName()+ TMP_SUFFIX);
        File linkFile = new File(sourceAbsolutePath);
        Path linkPath = linkFile.toPath();
        Path targetPath = targetFile.toPath();
        Path sourcePath = sourceFile.toPath();
        // FIXME: 2016/09/23 目前的方法还不能保证一定不会造成数据丢失，无法保证数据一致性
        boolean migrated = copyFile(sourcePath, targetPath);


        if(migrated) {

            // 重命名原sstable文件
            try {
                migrated = sourceFile.renameTo(tmpFile);
            } catch (Exception e) {
                LOG.error(e.getMessage(), e);
                migrated = false;
            }

            if(!migrated){
                if(!deleteFile(targetFile)){
                    LOG.warn("迁移失败，但目标文件未能删除：{}", targetFile.getAbsolutePath());
                }
                return false;
            }


            migrated = createSymbolicLink(linkPath, targetPath);
            if (migrated) {
                if(!deleteFile(tmpFile)){
                    LOG.warn("迁移成功，但临时文件未能删除：{}", tmpFile.getAbsolutePath());
                }
            }
            else{
                // 迁移失败需要还原sstable文件，否则会造成数据丢失
                // FIXME: 2016/09/23 目前还没有一个有效的方法能保证迁移失败后sstable文件一定能够还原成功
                //noinspection ResultOfMethodCallIgnored
                tmpFile.renameTo(new File(sourceAbsolutePath));
                if(!deleteFile(targetFile)){
                    LOG.warn("迁移失败，但目标文件以建立且未能删除：{}", targetFile.getAbsolutePath());
                }
            }
        }
        return migrated;
    }

}
