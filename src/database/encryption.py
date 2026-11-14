"""
Encryption utilities for secure password storage
"""
import os
from pathlib import Path
from cryptography.fernet import Fernet
from ..utils.logger import get_logger

logger = get_logger(__name__)


class EncryptionService:
    """Сервис для шифрования/дешифрования паролей"""
    
    def __init__(self, key_file: str = "data/.secret_key"):
        """
        Инициализация сервиса шифрования
        
        Args:
            key_file: Путь к файлу с ключом шифрования
        """
        self.key_file = Path(key_file)
        self.key = self._load_or_create_key()
        self.cipher = Fernet(self.key)
    
    def _load_or_create_key(self) -> bytes:
        """
        Загрузить существующий ключ или создать новый
        
        Returns:
            bytes: Ключ шифрования
        """
        if self.key_file.exists():
            logger.info(f"Загрузка ключа шифрования из {self.key_file}")
            with open(self.key_file, 'rb') as f:
                return f.read()
        else:
            logger.info(f"Создание нового ключа шифрования в {self.key_file}")
            key = Fernet.generate_key()
            
            # Создаем директорию если не существует
            self.key_file.parent.mkdir(parents=True, exist_ok=True)
            
            # Сохраняем ключ с ограниченными правами доступа
            with open(self.key_file, 'wb') as f:
                f.write(key)
            
            # Устанавливаем права доступа только для владельца
            os.chmod(self.key_file, 0o600)
            
            logger.info("✅ Новый ключ шифрования создан")
            return key
    
    def encrypt(self, plaintext: str) -> str:
        """
        Зашифровать строку
        
        Args:
            plaintext: Исходная строка
            
        Returns:
            str: Зашифрованная строка (base64)
        """
        if not plaintext:
            return ""
        
        encrypted_bytes = self.cipher.encrypt(plaintext.encode('utf-8'))
        return encrypted_bytes.decode('utf-8')
    
    def decrypt(self, encrypted: str) -> str:
        """
        Расшифровать строку
        
        Args:
            encrypted: Зашифрованная строка (base64)
            
        Returns:
            str: Расшифрованная строка
        """
        if not encrypted:
            return ""
        
        try:
            decrypted_bytes = self.cipher.decrypt(encrypted.encode('utf-8'))
            return decrypted_bytes.decode('utf-8')
        except Exception as e:
            logger.error(f"Ошибка дешифрования: {e}")
            raise ValueError("Не удалось расшифровать данные. Возможно, используется другой ключ.")
