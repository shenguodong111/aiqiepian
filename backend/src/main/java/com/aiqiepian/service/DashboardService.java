package com.aiqiepian.service;

import java.util.Map;

public interface DashboardService {

    /** GET /dashboard/summary：stats / pipeline / running_jobs / recent_works / system */
    Map<String, Object> summary();
}
