package io.github.tonymontanov.bookmap.moex.provider;

import java.util.HashSet;
import java.util.Set;

/**
 * Состояние связи провайдера в целом по стримам стакана его инструментов.
 *
 * <p>У Bookmap один признак связи на провайдер, а стримов — по два на
 * инструмент. Связь считается потерянной, пока хоть один стрим стакана лежит,
 * и восстановленной, когда поднялся последний. Лента сделок в расчёт не идёт:
 * у неликвидного инструмента она молчит часами, и «первого сообщения» после
 * переоткрытия можно ждать до следующей сделки — провайдер всё это время
 * показывал бы обрыв. Стакан же шлюз присылает снимком сразу после подписки.
 *
 * <p>Не потокобезопасно: живёт на потоке-диспетчере провайдера.
 */
final class ConnectionHealth {

    private final Set<String> down = new HashSet<>();

    /** Стакан инструмента упал. @return {@code true}, если до этого всё было живо — пора сообщить о потере связи */
    boolean markDown(String alias) {
        boolean wasHealthy = down.isEmpty();
        return down.add(alias) && wasHealthy;
    }

    /** Стакан инструмента поднялся. @return {@code true}, если упавших не осталось — пора сообщить о восстановлении */
    boolean markUp(String alias) {
        return down.remove(alias) && down.isEmpty();
    }

    /** Инструмент снят с подписки; возврат — как у {@link #markUp}. */
    boolean forget(String alias) {
        return markUp(alias);
    }

    boolean isHealthy() {
        return down.isEmpty();
    }
}
