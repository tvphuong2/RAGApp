package com.example.ragapp.modelprep

import org.json.JSONObject

data class ModelEntry(
    val name: String,
    val filename: String,
    val version: String,
    val sizeBytes: Long,
    val sha256: String,
    val quant: String?,
    val nCtxHint: Int?
)

data class ModelManifest(val models: List<ModelEntry>) {
    companion object {
        fun fromJson(json: String): ModelManifest {
            val root = JSONObject(json)
            val arr = root.getJSONArray("models")
            val list = MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ModelEntry(
                    name = o.getString("name"),
                    filename = o.getString("filename"),
                    version = o.getString("version"),
                    sizeBytes = o.getLong("size_bytes"),
                    sha256 = o.getString("sha256"),
                    quant = o.optString("quant", null),
                    nCtxHint = if (o.has("n_ctx_hint")) o.getInt("n_ctx_hint") else null
                )
            }
            return ModelManifest(list)
        }
    }
}
