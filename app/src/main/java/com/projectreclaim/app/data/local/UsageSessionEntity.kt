package com.projectreclaim.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_sessions")
data class UsageSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val platform: String,
    val usageType: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long? = null,
    val detectorVersion: String? = null,
    val lastConfirmedActiveTimeMillis: Long? = null,
)
