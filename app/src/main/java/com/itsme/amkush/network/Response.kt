package com.itsme.amkush.network

sealed class Response<out T> {
    data class Success<T>(val data: T) : Response<T>()
    data class Error(val message: String, val code: Int = -1) : Response<Nothing>()
    object Loading : Response<Nothing>()
}

fun <T> Response<T>.isSuccess(): Boolean = this is Response.Success

fun <T> Response<T>.isError(): Boolean = this is Response.Error

fun <T> Response<T>.isLoading(): Boolean = this is Response.Loading

fun <T> Response<T>.getDataOrNull(): T? = (this as? Response.Success)?.data

fun <T> Response<T>.getErrorMessage(): String? = (this as? Response.Error)?.message