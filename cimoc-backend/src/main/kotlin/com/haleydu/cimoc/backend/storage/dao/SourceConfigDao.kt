package com.haleydu.cimoc.backend.storage.dao

import com.haleydu.cimoc.backend.storage.Database

/**
 * `source_config` 表的读写：**只存用户改过的图源开关**。
 *
 * 图源清单本身来自代码反射（见 `SourceRegistry`），不进数据库——否则每次新增图源都要写迁移。
 * 表里没有某一行，就表示「用图源自己声明的默认值」（`getDefaultSource().getEnable()`）。
 *
 * 因此 [isEnabled] 必须拿到默认值才能回答，这个参数不能省。
 */
class SourceConfigDao(private val db: Database) {

    /** 用户显式设置的开关，`type -> enabled`。没出现在这里的图源用默认值。 */
    fun overrides(): Map<Int, Boolean> = db.read { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT type, enabled FROM source_config").use { rs ->
                buildMap { while (rs.next()) put(rs.getInt(1), rs.getInt(2) != 0) }
            }
        }
    }

    fun isEnabled(type: Int, default: Boolean): Boolean =
        overrides()[type] ?: default

    fun setEnabled(type: Int, enabled: Boolean) = db.write { conn ->
        conn.prepareStatement(
            "INSERT INTO source_config (type, enabled) VALUES (?, ?) " +
                "ON CONFLICT(type) DO UPDATE SET enabled = excluded.enabled"
        ).use { ps ->
            ps.setInt(1, type)
            ps.setInt(2, if (enabled) 1 else 0)
            ps.executeUpdate()
        }
        Unit
    }

    /** 恢复默认：删掉这一行即可，图源会重新按自己声明的默认值走。 */
    fun reset(type: Int) = db.write { conn ->
        conn.prepareStatement("DELETE FROM source_config WHERE type = ?").use { ps ->
            ps.setInt(1, type)
            ps.executeUpdate()
        }
        Unit
    }
}
