package com.example.data.repository

import com.example.data.local.dao.UserDao
import com.example.data.local.entities.UserEntity
import com.example.util.SecurityUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AuthResult {
    data class Success(val user: UserEntity) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

class AuthRepository(private val userDao: UserDao) {

    private val _currentUser = MutableStateFlow<UserEntity?>(null)
    val currentUser: StateFlow<UserEntity?> = _currentUser.asStateFlow()

    fun getAllStudentsFlow(): Flow<List<UserEntity>> = userDao.getAllStudentsFlow()
    fun getStudentCountFlow(): Flow<Int> = userDao.getStudentCountFlow()

    suspend fun login(username: String, password: String): AuthResult {
        if (username.isBlank() || password.isBlank()) {
            return AuthResult.Error("Username and password cannot be empty")
        }

        val user = userDao.getUserByUsername(username.trim().lowercase())
            ?: return AuthResult.Error("Account not found. Please register or check credentials.")

        val isMatch = SecurityUtils.verifyPassword(password, user.salt, user.passwordHash)
        if (!isMatch) {
            return AuthResult.Error("Incorrect password. Please try again.")
        }

        _currentUser.value = user
        return AuthResult.Success(user)
    }

    suspend fun register(
        username: String,
        password: String,
        fullName: String,
        role: String,
        gradeOrDept: String
    ): AuthResult {
        if (username.isBlank() || password.isBlank() || fullName.isBlank()) {
            return AuthResult.Error("Please fill in all required fields.")
        }
        if (password.length < 6) {
            return AuthResult.Error("Password must be at least 6 characters long.")
        }

        val existing = userDao.getUserByUsername(username.trim().lowercase())
        if (existing != null) {
            return AuthResult.Error("Username '$username' is already registered.")
        }

        val salt = SecurityUtils.generateSalt()
        val hash = SecurityUtils.hashPassword(password, salt)
        val avatarColor = when (role.uppercase()) {
            "TEACHER" -> "#0284C7"
            else -> "#10B981"
        }

        val newUser = UserEntity(
            username = username.trim().lowercase(),
            passwordHash = hash,
            salt = salt,
            role = role.uppercase(),
            fullName = fullName.trim(),
            gradeOrDept = gradeOrDept.trim(),
            avatarColorHex = avatarColor
        )

        val id = userDao.insertUser(newUser).toInt()
        val created = newUser.copy(id = id)
        _currentUser.value = created
        return AuthResult.Success(created)
    }

    fun logout() {
        _currentUser.value = null
    }

    fun setCurrentUser(user: UserEntity?) {
        _currentUser.value = user
    }
}
