package com.example.smartalarm.root;

import android.os.Bundle;

/**
 * root 进程对外暴露的窄接口。只列本 APP 需要的操作，不提供任意命令或任意 Provider 调用。
 * S1 阶段只有只读方法；写操作等 S1 通过后在 S2 再加。
 */
interface IClockBridge {
    /** 环境信息：uid、ROM、时钟版本、Provider 是否存在及其权限。只读。 */
    Bundle probe();

    /** 调用时钟业务接口 get_alarm_list，原样带回响应与耗时、异常。只读。 */
    Bundle readAlarmList();

    /** 只读独立对账：直接查时钟闹钟表，按与 get_alarm_list 相同的过滤条件数行。出错带 error，不返回假的 0。 */
    Bundle countAlarms();

    /** 只读：时钟界面里是否有未保存的新建/编辑面板（读 dumpsys，固定命令）。读不清时按“有”返回，宁可不动用户的现场。 */
    boolean isClockEditorOpen();

    /** 写：新建一条一次性、开启的闹钟（走时钟业务接口 add_alarm）。参数在服务端校验。 */
    Bundle addAlarm(int hour, int minute, String label);

    /** 写：按真实编号启用一条已有闹钟（走 enable_alarm，不带时分，避免走“按时间查找”分支）。 */
    Bundle enableAlarm(long alarmId);
}
