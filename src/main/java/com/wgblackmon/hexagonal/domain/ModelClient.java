package com.wgblackmon.hexagonal.domain;

/**
 * The domain's driven port for talking to a language model — the hexagonal boundary that
 * keeps {@link Agent} free of any particular provider's SDK types.
 *
 * @author  Bill Blackmon
 * @version 1.0
 * @since   2026-10-18
 * @updated 2026-10-18
 */
public interface ModelClient {

    ModelResponse send(ModelRequest request);
}
