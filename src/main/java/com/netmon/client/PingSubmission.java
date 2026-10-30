package com.netmon.client;

import com.netmon.ping.PingOperationResponse;

public record PingSubmission(PingOperationResponse operation, boolean created) {
}
