package com.example.videocall_marching_language.utils;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.util.Base64;

@Converter
@Component
public class StringCryptoConverter implements AttributeConverter<String, String> {
    private static final String ALGORITHM = "AES/ECB/PKCS5Padding";

    private static byte[] KEY;

    @Value("${api.secret.key:MySecretKey12345}")
    public void setKey(String key) {
        String paddedKey = String.format("%-16s", key).substring(0, 16);
        StringCryptoConverter.KEY = paddedKey.getBytes();
    }

    @Override
    public String convertToDatabaseColumn(String plainText) {
        if (plainText == null) return null;
        try {
            Key key = new SecretKeySpec(KEY, "AES");
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes());
            // Trả về chuỗi Base64 để lưu xuống CSDL an toàn
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Lỗi mã hóa dữ liệu API Key.", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String cipherText) {
        if (cipherText == null) return null;
        try {
            Key key = new SecretKeySpec(KEY, "AES");
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(cipherText));
            // Trả về API Key thật
            return new String(decryptedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Lỗi giải mã dữ liệu API Key.", e);
        }
    }
}
