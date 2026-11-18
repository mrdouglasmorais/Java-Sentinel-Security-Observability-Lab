package dev.sentinel.simulation;

public enum FaultTarget {
    /** The external risk scoring dependency called on every transfer. */
    RISK_ENGINE,
    /** The PostgreSQL transaction that moves funds, executed while account rows are locked. */
    DATABASE
}
