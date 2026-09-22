package br.com.conde.bytecode.compilador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import br.com.conde.bytecode.Expressao;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * O juiz destes testes é a própria JVM.
 *
 * <p>Cada {@code compilar} passa o bytecode pelo verificador: pilha declarada
 * a menos, descritor que não bate, índice de pool inválido — qualquer um
 * desses vira {@code VerifyError} ou {@code ClassFormatError} na hora de
 * carregar. Um teste que chega a executar já provou que o arquivo está certo.
 */
@DisplayName("compilador de expressões para bytecode")
class CompiladorTest {

  @Nested
  @DisplayName("aritmética")
  class Aritmetica {

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({
      "'1 + 1', 2",
      "'2 + 3 * 4', 14",
      "'(2 + 3) * 4', 20",
      "'10 / 4', 2.5",
      "'10 % 3', 1",
      "'-5 + 2', -3",
      "'2 - -3', 5",
      "'1e3', 1000",
      "'1.5e-2', 0.015",
      "'  7  ', 7",
    })
    void calculaOEsperado(String fonte, double esperado) {
      assertThat(Compilador.compilar(fonte).calcular()).isEqualTo(esperado);
    }

    @Test
    @DisplayName("a potência associa à direita")
    void potenciaAssociaADireita() {
      // 2^(3^2) = 512, não (2^3)^2 = 64.
      assertThat(Compilador.compilar("2^3^2").calcular()).isEqualTo(512.0);
    }

    @Test
    @DisplayName("o menos unário une mais fraco que a potência")
    void menosUnarioUneMaisFraco() {
      // −(2²), como em matemática, em Java e em Python. Planilha daria 4.
      assertThat(Compilador.compilar("-2^2").calcular()).isEqualTo(-4.0);
      assertThat(Compilador.compilar("(-2)^2").calcular()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("divisão por zero segue o IEEE 754, não lança")
    void divisaoPorZero() {
      assertThat(Compilador.compilar("1 / 0").calcular()).isInfinite();
      assertThat(Compilador.compilar("0 / 0").calcular()).isNaN();
    }

    @Test
    @DisplayName("mil expressões sorteadas dão o mesmo que o próprio Java")
    void concordaComOJava() {
      // A régua é a linguagem: se `a*b + c/d` compilado aqui diverge do mesmo
      // cálculo feito em Java, é o bytecode que está errado.
      Random sorteio = new Random(20260922);

      for (int i = 0; i < 1000; i += 1) {
        double a = (sorteio.nextDouble() - 0.5) * 1000;
        double b = (sorteio.nextDouble() - 0.5) * 1000;
        double c = (sorteio.nextDouble() - 0.5) * 1000;

        var compilada = Compilador.compilar("a * b + c / 2 - (a - c) * 0.5");

        assertThat(compilada.calcular(a, b, c))
            .as("divergiu com a=%s b=%s c=%s", a, b, c)
            .isEqualTo(a * b + c / 2 - (a - c) * 0.5);
      }
    }
  }

  @Nested
  @DisplayName("variáveis")
  class Variaveis {

    @Test
    @DisplayName("a ordem de descoberta define a posição no vetor")
    void ordemDeDescoberta() {
      var compilada = Compilador.compilar("b - a");

      assertThat(compilada.variaveis()).containsExactly("b", "a");
      assertThat(compilada.calcular(10, 3)).isEqualTo(7.0);
    }

    @Test
    @DisplayName("a mesma variável duas vezes ocupa uma posição só")
    void mesmaVariavelUmaPosicao() {
      var compilada = Compilador.compilar("x * x + x");

      assertThat(compilada.variaveis()).containsExactly("x");
      assertThat(compilada.calcular(3)).isEqualTo(12.0);
    }

    @Test
    @DisplayName("faltar variável reclama dizendo quais são")
    void faltarVariavelReclama() {
      var compilada = Compilador.compilar("a + b");

      assertThatThrownBy(() -> compilada.calcular(1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("a, b");
    }

    @Test
    @DisplayName("o objeto compilado é reutilizável e sem estado")
    void reutilizavel() {
      var compilada = Compilador.compilar("a * 2");

      assertThat(compilada.calcular(1)).isEqualTo(2.0);
      assertThat(compilada.calcular(21)).isEqualTo(42.0);
      assertThat(compilada.calcular(-1)).isEqualTo(-2.0);
    }
  }

  @Nested
  @DisplayName("funções")
  class Funcoes {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "'raiz(16)', 4",
      "'abs(-3)', 3",
      "'piso(2.9)', 2",
      "'teto(2.1)', 3",
      "'arredondar(2.5)', 2",
      "'min(3, 7)', 3",
      "'max(3, 7)', 7",
      "'potencia(2, 10)', 1024",
      "'exp(0)', 1",
      "'log(1)', 0",
    })
    void chamamMath(String fonte, double esperado) {
      assertThat(Compilador.compilar(fonte).calcular()).isEqualTo(esperado);
    }

    @Test
    @DisplayName("as trigonométricas batem com o java.lang.Math")
    void trigonometricasBatem() {
      assertThat(Compilador.compilar("sen(a)").calcular(1.0)).isEqualTo(Math.sin(1.0));
      assertThat(Compilador.compilar("cos(a)").calcular(1.0)).isEqualTo(Math.cos(1.0));
      assertThat(Compilador.compilar("tan(a)").calcular(1.0)).isEqualTo(Math.tan(1.0));
    }

    @Test
    @DisplayName("arredondar usa rint: 2.5 vai para 2 e 3.5 vai para 4")
    void arredondarMeioPar() {
      // `Math.rint` arredonda para o par mais próximo, que é a regra do IEEE
      // 754 e o padrão de quem faz estatística — não é "sempre para cima".
      assertThat(Compilador.compilar("arredondar(2.5)").calcular()).isEqualTo(2.0);
      assertThat(Compilador.compilar("arredondar(3.5)").calcular()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("funções aninhadas e com expressão dentro")
    void aninhadas() {
      assertThat(Compilador.compilar("raiz(a*a + b*b)").calcular(3, 4)).isEqualTo(5.0);
      assertThat(Compilador.compilar("max(min(5, 3), 1)").calcular()).isEqualTo(3.0);
      assertThat(Compilador.compilar("raiz(raiz(16))").calcular()).isEqualTo(2.0, within(1e-12));
    }

    @Test
    @DisplayName("número errado de argumentos é recusado")
    void argumentosErrados() {
      assertThatThrownBy(() -> Compilador.compilar("raiz(1, 2)"))
          .isInstanceOf(ErroDeExpressao.class)
          .hasMessageContaining("recebe 1 argumento");

      assertThatThrownBy(() -> Compilador.compilar("min(1)")).hasMessageContaining("recebe 2 argumento");
    }

    @Test
    @DisplayName("função desconhecida lista as que existem")
    void funcaoDesconhecida() {
      assertThatThrownBy(() -> Compilador.compilar("inventada(1)"))
          .isInstanceOf(ErroDeExpressao.class)
          .hasMessageContaining("Função desconhecida")
          .hasMessageContaining("raiz");
    }
  }

  @Nested
  @DisplayName("erros de sintaxe")
  class Erros {

    @Test
    @DisplayName("dizem onde foi")
    void dizemOnde() {
      assertThatThrownBy(() -> Compilador.compilar("1 + $ 2"))
          .isInstanceOf(ErroDeExpressao.class)
          .hasMessageContaining("'$'")
          .hasMessageContaining("posição 4");
    }

    @ParameterizedTest(name = "recusa {0}")
    @CsvSource({"'1 +'", "'(1'", "'1)'", "'1 2'", "''", "'*'", "'raiz('", "'1 + + '"})
    void recusaOQueNaoEExpressao(String fonte) {
      assertThatThrownBy(() -> Compilador.compilar(fonte)).isInstanceOf(ErroDeExpressao.class);
    }

    @Test
    @DisplayName("o caractere inesperado não é engolido em silêncio")
    void naoEngoleEmSilencio() {
      // Um lexer apressado calcularia 2 e ignoraria o resto.
      assertThatThrownBy(() -> Compilador.compilar("2 $ 3")).isInstanceOf(ErroDeExpressao.class);
    }
  }

  @Nested
  @DisplayName("a classe gerada")
  class ClasseGerada {

    @Test
    @DisplayName("implementa a interface de verdade, sem reflexão na chamada")
    void implementaAInterface() {
      var compilada = Compilador.compilar("a + 1");

      assertThat(compilada.expressao()).isInstanceOf(Expressao.class);
      assertThat(compilada.expressao().calcular(new double[] {41})).isEqualTo(42.0);
    }

    @Test
    @DisplayName("começa com CAFEBABE e declara a versão do Java 8")
    void assinaturaEVersao() {
      byte[] bytes = Compilador.compilar("1").bytes();

      assertThat(bytes[0] & 0xFF).isEqualTo(0xCA);
      assertThat(bytes[1] & 0xFF).isEqualTo(0xFE);
      assertThat(bytes[2] & 0xFF).isEqualTo(0xBA);
      assertThat(bytes[3] & 0xFF).isEqualTo(0xBE);
      assertThat(((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF)).isEqualTo(52);
    }

    @Test
    @DisplayName("duas compilações geram classes diferentes")
    void classesDiferentes() {
      var uma = Compilador.compilar("1 + 1");
      var outra = Compilador.compilar("1 + 1");

      assertThat(uma.expressao().getClass()).isNotEqualTo(outra.expressao().getClass());
      assertThat(uma.calcular()).isEqualTo(outra.calcular());
    }

    @Test
    @DisplayName("expressão bem fundo não estoura o gerador")
    void expressaoFunda() {
      // 200 somas encadeadas: a pilha do bytecode cresce de dois em dois e o
      // `max_stack` declarado precisa acompanhar, senão a JVM recusa.
      String fonte = "1" + " + 1".repeat(200);

      assertThat(Compilador.compilar(fonte).calcular()).isEqualTo(201.0);
    }
  }
}
