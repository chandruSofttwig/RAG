package com.localrag.llm;

public class OllamaException extends RuntimeException {
  public OllamaException(String message) {
    super(message);
  }

  public OllamaException(String message, Throwable cause) {
    super(message, cause);
  }
}
