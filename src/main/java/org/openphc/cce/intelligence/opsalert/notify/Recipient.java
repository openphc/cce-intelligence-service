package org.openphc.cce.intelligence.opsalert.notify;

import java.util.List;

public record Recipient(String to, List<String> cc) {
}
