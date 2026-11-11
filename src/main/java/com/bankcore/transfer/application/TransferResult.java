package com.bankcore.transfer.application;

import com.bankcore.transfer.domain.Transfer;

public record TransferResult(Transfer transfer, boolean replayed) {
}
