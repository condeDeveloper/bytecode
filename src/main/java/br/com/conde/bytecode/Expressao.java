package br.com.conde.bytecode;

/**
 * O que uma expressão compilada vira.
 *
 * <p>A classe gerada em tempo de execução implementa esta interface, e é por
 * isso que dá para chamar o resultado sem reflexão: um {@code invokeinterface}
 * comum, na velocidade de qualquer outra chamada Java.
 */
public interface Expressao {

  /** Calcula, com as variáveis na ordem em que foram declaradas. */
  double calcular(double[] variaveis);
}
