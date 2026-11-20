package com.adrianrevilla.trading.architecture.violations.domain;

import com.adrianrevilla.trading.architecture.violations.adapter.out.one.AdapterOne;

/** Fixture: the domain depends on an adapter (the dependency points outwards). */
public class DomainCallingAdapter {
    AdapterOne adapter;
}
