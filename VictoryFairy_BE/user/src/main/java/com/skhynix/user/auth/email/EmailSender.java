package com.skhynix.user.auth.email;

public interface EmailSender {

    void sendVerificationCode(String email, String code);
}
