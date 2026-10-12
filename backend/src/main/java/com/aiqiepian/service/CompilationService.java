package com.aiqiepian.service;

import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.Compilation;

import java.util.Map;

/** 合集（M4-2）实现契约见 CompilationServiceImpl */
public interface CompilationService {

    PageResult<Compilation> list(Integer page, Integer size);

    /** body: {title, work_ids:[...]}：校验成员作品状态/产物并快照总时长，建 DRAFT 合集 */
    Compilation create(Map<String, Object> body);

    /** 立即返回（置 RENDERING），concat 渲染在独立后台线程执行 */
    Compilation render(long id);

    void delete(long id);

    /** 独立小调度（CompilationScheduler，5s）：RENDERING 假进度推进 + 重启后孤儿任务重投 */
    void schedulerTick();
}
