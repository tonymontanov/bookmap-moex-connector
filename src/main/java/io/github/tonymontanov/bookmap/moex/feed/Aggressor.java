package io.github.tonymontanov.bookmap.moex.feed;

/** Инициатор сделки — тот, чья заявка исполнилась о стоящую в стакане. */
public enum Aggressor {
    BUYER,
    SELLER,
    /** Шлюз сторону не сообщил; её определяет получатель по стакану. */
    UNKNOWN
}
