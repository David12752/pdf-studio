package com.pdfsigner.app.domain.service

/** Thrown when a PDF requires a password to open. */
class PdfPasswordRequiredException : Exception("PDF is password-protected")

/** Thrown when the supplied password is incorrect. */
class WrongPasswordException : Exception("Wrong password")
