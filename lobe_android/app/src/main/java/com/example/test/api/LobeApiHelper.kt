package com.example.test.api

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.StringRequest
import com.example.test.RequestSingleton
import com.example.test.env.Logger
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.BlobDataPart
import com.github.kittinunf.fuel.core.Method
import com.github.kittinunf.fuel.core.ResponseDeserializable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.Instant


data class Project(
    var id: String,
    var name: String
)


data class Example(
    val id: Number = 0,
    val exampleId: String = "",
    val lobeId: String = "",
    val type: String = "",
    val item: String = "",
    val hash: String = "",
    val isTest: Boolean = false,
    val modified: Long = 0
) {

    inline fun <reified T> Gson.fromJson(json: String) =
        fromJson<T>(json, object : TypeToken<T>() {}.type)

    //Example Deserializer
    class Deserializer : ResponseDeserializable<List<Example>> {

        val exampleListType = object : TypeToken<List<Example>>() {}.type

        override fun deserialize(content: String) =
            Gson().fromJson<List<Example>>(content, exampleListType)
    }

}

class LobeApiHelper {


    companion object {
        private val LOGGER: Logger = Logger()

        var urlHost = "192.168.0.14"
        var projectId = ""


        fun getProjects(
            context: Context,
            callback: (project: List<Project>) -> Unit
        ) {
            val url: String =
                "http://${this.urlHost}:38101/data/v1/project"

            val projects: ArrayList<Project> = ArrayList<Project>()

            val stringRequest = StringRequest(
                Request.Method.GET, url,
                Response.Listener<String> { response ->
                    val projectArray = JSONArray(response)

                    for (i in 0 until projectArray.length()) {
                        val projectId = projectArray[i].toString()
                        this.getProjectDetails(
                            context,
                            projectId
                        ) { project ->
                            projects.add(project)
                            if (projects.count() == projectArray.length()) {
                                // done -- final callback
                                callback?.invoke(projects)
                            }
                        }
                    }
                },
                Response.ErrorListener { error -> LOGGER.e(error, "error getting projects") })


            // Access the RequestQueue through your singleton class.
            RequestSingleton.getInstance(context).addToRequestQueue(stringRequest)
        }

        fun getProjectDetails(
            context: Context,
            projectId: String,
            callback: (project: Project) -> Unit
        ) {
            val url: String =
                "http://${this.urlHost}:38101/data/v1/project/${projectId}"


            val stringRequest = StringRequest(
                Request.Method.GET, url,
                Response.Listener<String> { response ->
                    val projects = JSONObject(response)

                    val meta = projects.getJSONObject("meta")
                    val name = meta.getString("name")

                    val project = Project(projectId, name)
                    callback?.invoke(project)
                },
                Response.ErrorListener { error ->
                    LOGGER.e(
                        error,
                        "error getting project details"
                    )
                })


            // Access the RequestQueue through your singleton class.
            RequestSingleton.getInstance(context).addToRequestQueue(stringRequest)
        }

        fun unloadProjects(context: Context) {
            val url: String =
                "http://${this.urlHost}:38101/graphql"

            val params = JSONObject()
            val variables = JSONObject()
            variables.put("projectId", projectId)
            params.put(
                "query",
                "mutation UnloadAllProjects{unloadAllProjects}"
            )

            val stringRequest = JsonObjectRequest(
                Request.Method.POST, url, params,
                Response.Listener<JSONObject> { response ->
                    this.LOGGER.i("Response: %s".format(response.toString()))
                },
                Response.ErrorListener { error -> LOGGER.e(error, "error unloading projects") })


            // Access the RequestQueue through your singleton class.
            RequestSingleton.getInstance(context).addToRequestQueue(stringRequest)
        }

        private var shouldUnload = false
        fun loadProject(
            context: Context, projectId: String,
            callback: (success: Boolean) -> Unit
        ) {
//            curl 'http://localhost:38101/graphql' \
//            -H "Content-Type: application/json" \
//            -d '{
//            "query": "mutation LoadProject($projectId:ID!){loadProject(projectId:$projectId)}",
//            "variables": {"projectId": "8f19ac0a-fc83-4505-b5f9-eaa3942220d9"}
//        }'
            if (shouldUnload) {
                unloadProjects(context)
                shouldUnload = false
            }

            val url: String =
                "http://${this.urlHost}:38101/graphql"

            val params = JSONObject()
            val variables = JSONObject()
            variables.put("projectId", projectId)
            params.put(
                "query",
                "mutation LoadProject(\$projectId:ID!){loadProject(projectId:\$projectId)}"
            )
            params.put("variables", variables)

            val stringRequest = JsonObjectRequest(
                Request.Method.POST, url, params,
                Response.Listener<JSONObject> { response ->
                    this.LOGGER.i("Response: %s".format(response.toString()))
                    // this isn't reliable
//                    val success = response.getJSONObject("data").getBoolean("loadProject")
                    callback(true)
                },
                Response.ErrorListener { error ->
                    LOGGER.e(error, "error loading project")
                    callback(false)
                })

            stringRequest.setRetryPolicy(
                DefaultRetryPolicy(
                    10000,
                    DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                    DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
                )
            )


            // Access the RequestQueue through your singleton class.
            RequestSingleton.getInstance(context).addToRequestQueue(stringRequest)
        }

        fun getPrediction(context: Context) {
            val url: String =
                "http://${this.urlHost}:38100/predict/${projectId}"

            val stringRequest = StringRequest(
                Request.Method.GET, url,
                Response.Listener<String> { response ->
                    this.LOGGER.i("Response: %s".format(response.toString()))
                },
                Response.ErrorListener { error -> LOGGER.e(error, "error getting prediction") })


            // Access the RequestQueue through your singleton class.
            RequestSingleton.getInstance(context).addToRequestQueue(stringRequest)
        }


        fun postPrediction(
            context: Context,
            base64String: String,
            callback: (predictedLabel: String, confidence: Int) -> Unit
        ) {
            val url: String = "http://${this.urlHost}:38100/predict/${projectId}"

            val stringRequest = @RequiresApi(Build.VERSION_CODES.N)
            object : StringRequest(
                Request.Method.POST,
                url,
                Response.Listener<String> { response ->
                    val jsonObject = JSONObject(response)
                    val output = (jsonObject["outputs"] as JSONObject)
                    val predictedLabel = (output["Prediction"] as JSONArray)[0].toString()
//                    label!!.text = predictedLabel
                    val labels = output.getJSONArray("Labels")

                    for (i in 0 until labels.length()) {
                        val item = labels.getJSONArray(i)
                        if (item[0].toString() == predictedLabel) {
                            val item1 = item[1]
                            val confidence = ((item1 as Double) * 100).toInt()
                            callback?.invoke(predictedLabel, confidence)
                        }
                    }
                },
                Response.ErrorListener { error -> LOGGER.e(error, "error getting prediction") }) {
                override fun getBody(): ByteArray {
                    var body: String = "{\"inputs\":{\"Image\":\"%s\"}}".format(base64String)
                    return body.toByteArray()
                }
            }

            // Access the RequestQueue through your singleton class.
            RequestSingleton.getInstance(context).addToRequestQueue(stringRequest)
        }


        @RequiresApi(Build.VERSION_CODES.O)
        fun postExample(
            context: Context,
            runInBackground: (r: Runnable) -> Unit,
            base64String: ByteArray,
            label: String?
        ) {
            // Since this might start training, let's mark shouldUnload to improve
            // performance in demonstrations.
            // The right thing to do would to track this specific projectId and unload only it
            shouldUnload = true

            val url: String =
                "http://${urlHost}:38101/data/v1/project/${projectId}/datastream"

            val stringRequest = StringRequest(
                Request.Method.GET, url,
                Response.Listener<String> { response ->
                    val datastreams = JSONArray(response)
                    var targets: String = ""
                    var inputs: String = ""
                    for (i in 0 until datastreams.length()) {
                        val item = datastreams.getJSONObject(i)
                        if (item["classes"] != null) {
                            val classes = item.getJSONArray("classes")

                            if (classes.isNull(0)) {
                                inputs = item.getString("lobeId")
                            } else {
                                targets = item.getString("lobeId")
                            }
                        }
                    }

                    // Upload image to INPUT ds
                    val jsonBody =
                        """{"isTest":false, "item":"", "type": "image","exampleId":"", "timestamp":${Instant.now()
                            .toEpochMilli()}}"""
                    val itemUrl: String =
                        "http://${urlHost}:38101/data/v1/project/${projectId}/datastream/${inputs}/item"

                    val formData = listOf("items[]" to jsonBody)
                    LOGGER.i("Starting upload")

                    runInBackground(
                        Runnable {
                            Fuel.upload(itemUrl, Method.POST, formData)
                                .add(
                                    BlobDataPart(
                                        ByteArrayInputStream(base64String),
                                        name = "file",
                                        filename = "somefile.jpeg"
                                    )
                                )
                                .responseObject(Example.Deserializer()) { req, res, result ->
                                    LOGGER.i(res.toString())

                                    //result is of type Result<Example, Exception>
                                    val (example, err) = result

                                    if (err != null) {
                                        LOGGER.e("Error uploading image: %s", err)
                                        return@responseObject
                                    }

                                    if (label != null && example != null && example.count() > 0) {
                                        // Upload label to TARGET ds
                                        val jsonBodyItem =
                                            """{"isTest":false, "item":"$label", "type": "text","exampleId":"${example[0].exampleId}", "timestamp":${Instant.now()
                                                .toEpochMilli()}}"""
                                        val itemLabelUrl: String =
                                            "http://${urlHost}:38101/data/v1/project/$projectId/datastream/$targets/item"


                                        runInBackground(
                                            Runnable {
                                                val response = Fuel.upload(
                                                    itemLabelUrl,
                                                    Method.POST,
                                                    listOf("items[]" to jsonBodyItem)
                                                ).response()

                                                LOGGER.i(response.toString())
                                            })
                                    }
                                }

                        })
                },
                Response.ErrorListener { error -> LOGGER.e(error, "error getting datastreams") })


            // Access the RequestQueue through your singleton class.
            RequestSingleton.getInstance(context).addToRequestQueue(stringRequest)
        }
    }
}