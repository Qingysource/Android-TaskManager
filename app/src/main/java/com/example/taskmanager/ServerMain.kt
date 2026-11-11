package com.example.taskmanager

import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

@Keep
object ServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val server = ProcessServiceServer()
        try {
            val serverSocket = ServerSocket(12345, 50, InetAddress.getByName("127.0.0.1"))
            println("TCP Server registered on 127.0.0.1:12345. Waiting for connections...")

            while (true) {
                val socket: Socket = serverSocket.accept()
                try {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val request = reader.readLine() ?: ""

                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                    val data = when (request) {
                        "PERF" -> server.getPerformanceData()
                        else -> server.getProcessList()
                    }
                    writer.write(data)
                    writer.flush()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    socket.close()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("Server error: ${e.message}")
        }
    }
}