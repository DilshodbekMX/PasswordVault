package com.example.pvlab.clip

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Mechanism (e): one-time WorkManager request with an initial delay. */
class ClearWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Lab.clearNow(
            applicationContext,
            "work",
            inputData.getString("run"),
            inputData.getLong("copyEl", 0L),
            inputData.getLong("copyUp", 0L),
            inputData.getLong("delayMs", 0L),
            "worker attempt=$runAttemptCount",
        )
        return Result.success()
    }
}
