package stageguard.sctimetable.service

import kotlinx.coroutines.*
import net.mamoe.mirai.utils.info
import org.quartz.*
import org.quartz.Job
import stageguard.sctimetable.AbstractPluginManagedService
import stageguard.sctimetable.PluginMain
import stageguard.sctimetable.database.Database
import stageguard.sctimetable.database.model.SchoolTimetable
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

/**
 * 考虑到bot可能会长期运行(指超过半年或者一个学期)，将时间相关的所有时间放在TimeProviderService中更新
 *
 * 包含以下定时更新属性：
 * - [currentYear] 当前年份，由 [YearUpdater] 在每年 1 月1  日0 0:00:10 更新
 * - [currentSemester] 当前学期，由 [SemesterUpdater] 在每年 2 月 1 日 00:00 和 8 月 1 日 00:00:10 长假时更新
 * - [currentWeekPeriod] 由 [SchoolWeekPeriodUpdater] 在每周一的 00:00:10 更新，不同学校同一时间的周数不同
 *
 * 以上时间均为 ```UTF+8``` 时间 ```ZoneId.of("Asia/Shanghai")```
 **/
object TimeProviderService : AbstractPluginManagedService(Dispatchers.IO) {

    private const val JOB_GROUP = "TimeProviderServiceGroup"
    /**
     * 当前年份
     **/
    var currentYear: Int = 0
    /**
     * 当前学期，```1```表示秋季学期，```2```表示夏季学期
     **/
    var currentSemester: Int = 0
    /**
     * 当前周数，```Map```中的```key```为学校id，```value```为当前周数。
     **/
    var currentWeekPeriod: MutableMap<Int, Int> = mutableMapOf()

    val currentSemesterBeginYear: Int
        get() = if(currentSemester == 2) currentYear - 1 else currentYear

    val currentTimeStamp: LocalDate
        get() = LocalDate.now(ZoneId.of("Asia/Shanghai"))

    private val scheduledQuartzJob: MutableList<Pair<JobDetail, Trigger>> = mutableListOf(
        //Scheduled
        JobBuilder.newJob(YearUpdater::class.java).apply {
            withIdentity(JobKey.jobKey("YearUpdaterJob", JOB_GROUP))
        }.build() to TriggerBuilder.newTrigger().apply {
            withIdentity(TriggerKey.triggerKey("YearUpdaterTrigger", JOB_GROUP))
            withSchedule(CronScheduleBuilder.cronSchedule("10 0 0 1 1 ? *"))
            startNow()
        }.build(),
        JobBuilder.newJob(SemesterUpdater::class.java).apply {
            withIdentity(JobKey.jobKey("SemesterUpdaterJob", JOB_GROUP))
        }.build() to TriggerBuilder.newTrigger().apply {
            withIdentity(TriggerKey.triggerKey("SemesterUpdaterTrigger", JOB_GROUP))
            withSchedule(CronScheduleBuilder.cronSchedule("10 0 0 15 2,8 ? *"))
            startNow()
        }.build(),
        JobBuilder.newJob(SchoolWeekPeriodUpdater::class.java).apply {
            withIdentity(JobKey.jobKey("SchoolWeekPeriodUpdaterJob", JOB_GROUP))
        }.build() to TriggerBuilder.newTrigger().apply {
            withIdentity(TriggerKey.triggerKey("SchoolWeekPeriodUpdaterTrigger", JOB_GROUP))
            withSchedule(CronScheduleBuilder.cronSchedule("10 0 0 ? * 2 *"))
            startNow()
        }.build(),
        //immediate start once
        JobBuilder.newJob(YearUpdater::class.java).build() to TriggerBuilder.newTrigger().startNow().build(),
        JobBuilder.newJob(SemesterUpdater::class.java).build() to TriggerBuilder.newTrigger().startNow().build(),
        JobBuilder.newJob(SchoolWeekPeriodUpdater::class.java).build() to TriggerBuilder.newTrigger().startNow().build(),
    )


    override suspend fun main() {
        PluginMain.quartzScheduler.apply {
            scheduledQuartzJob.forEach { scheduleJob(it.first, it.second) }
        }.start()
        PluginMain.logger.info { "TimeProviderServices(${scheduledQuartzJob.joinToString(", ") { it.first.key.name }}) have started." }
        //unlimited job, kotlin still has no scheduler framework like quartz
        //awaitCancellation()
    }

    fun immediateUpdateSchoolWeekPeriod() {
        PluginMain.quartzScheduler.apply {
            scheduleJob(
                JobBuilder.newJob(SchoolWeekPeriodUpdater::class.java).apply {
                    withIdentity(JobKey.jobKey("ImmediateSchoolWeekPeriodUpdaterJob", JOB_GROUP))
                }.build(),
                TriggerBuilder.newTrigger().startNow().build()
            )
        }.start()
    }

    class YearUpdater : Job {
        override fun execute(context: JobExecutionContext?) {
            currentYear = LocalDate.now(ZoneId.of("Asia/Shanghai")).year
            PluginMain.logger.info { "Job YearUpdater is executed. (currentYear -> $currentYear)" }
        }
    }
    class SemesterUpdater: Job {
        override fun execute(context: JobExecutionContext?) {
            currentSemester = if(LocalDate.now(ZoneId.of("Asia/Shanghai")).monthValue in 3..7) 2 else 1
            PluginMain.logger.info { "Job SemesterUpdater is executed. (currentSemester -> $currentSemester)" }
        }
    }
    class SchoolWeekPeriodUpdater: Job {
        override fun execute(context: JobExecutionContext?) {
            Database.query {
                val timetables = SchoolTimetable.all()
                for (ttb in timetables) {
                    val addTime = LocalDate.parse(ttb.timeStampWhenAdd)
                    val dayAddedBasedWeek = addTime.dayOfWeek.value + (currentTimeStamp.toEpochDay() - addTime.toEpochDay())
                    val result = if(dayAddedBasedWeek <= 7) { 0 } else { ceil((dayAddedBasedWeek / 7).toFloat()).toInt() }
                    if(currentWeekPeriod.containsKey(ttb.schoolId)) currentWeekPeriod.remove(ttb.schoolId)
                    currentWeekPeriod[ttb.schoolId] = ttb.weekPeriodWhenAdd + result
                }
            }
            PluginMain.logger.info { "Job SchoolWeekPeriodUpdater is executed." }
        }
    }
}