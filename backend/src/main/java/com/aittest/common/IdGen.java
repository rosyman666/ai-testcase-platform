package com.aittest.common;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 业务单号生成器（见 §8.2）：{@code D20261107-000123} / {@code T...} / {@code R...}。
 *
 * <p>实现取舍：**进程内计数器 + 数据库唯一索引兜底重试**。单机单体应用每天几百条，
 * 用数据库序列表反而是过度设计；跨实例冲突由 {@code uk(doc_no)} 兜住，调用方捕获唯一键冲突重试即可。
 * 日期取 <b>UTC</b>，与库内时间口径一致——否则北京时间 08:00 前生成的单号会与昨天重号。</p>
 *
 * @author 寇豆码
 */
@Component
public class IdGen {

    /** 文档单号前缀。 */
    private static final String PREFIX_DOC = "D";

    /** 任务单号前缀。 */
    private static final String PREFIX_TASK = "T";

    /** 执行批次单号前缀。 */
    private static final String PREFIX_RUN = "R";

    /** 单号内日期格式：yyyyMMdd。 */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    /** 序号格式（6 位，单日上限 999999）。 */
    private static final String SEQ_FORMAT = "%06d";

    /** 当前计数所属日期（yyyyMMdd），跨天时重置计数。 */
    private String currentDay = "";

    /** 当日序号。 */
    private int currentSeq = 0;

    /**
     * 生成文档单号。
     *
     * @return 形如 {@code D20261107-000001}
     */
    public String docNo() {
        return next(PREFIX_DOC);
    }

    /**
     * 生成任务单号。
     *
     * @return 形如 {@code T20261107-000001}
     */
    public String taskNo() {
        return next(PREFIX_TASK);
    }

    /**
     * 生成执行批次单号。
     *
     * @return 形如 {@code R20261107-000001}
     */
    public String runNo() {
        return next(PREFIX_RUN);
    }

    /**
     * 生成单号，跨天自动重置序号。
     *
     * @param prefix 前缀
     * @return 业务单号
     */
    private synchronized String next(String prefix) {
        String day = LocalDate.now(ZoneOffset.UTC).format(DAY_FORMAT);
        if (!day.equals(this.currentDay)) {
            this.currentDay = day;
            this.currentSeq = 0;
        }
        this.currentSeq++;
        return prefix + day + "-" + String.format(SEQ_FORMAT, this.currentSeq);
    }
}
