package com.network.client;

import com.network.ping.PingOperationResponse;

public record PingSubmission(PingOperationResponse operation, boolean created) {
}
