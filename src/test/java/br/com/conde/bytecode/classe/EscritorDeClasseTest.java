package br.com.conde.bytecode.classe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.conde.bytecode.CarregadorEmMemoria;
import br.com.conde.bytecode.compilador.Compilador;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("o arquivo .class")
class EscritorDeClasseTest {

  @Nested
  @DisplayName("pool de constantes")
  class Pool {

    @Test
    @DisplayName("o índice começa em 1, porque 0 significa nenhum")
    void indiceComecaEmUm() {
      PoolDeConstantes pool = new PoolDeConstantes();

      assertThat(pool.tamanho()).isEqualTo(1);
      assertThat(pool.utf8("oi")).isEqualTo(1);
      assertThat(pool.tamanho()).isEqualTo(2);
    }

    @Test
    @DisplayName("long e double ocupam duas posições")
    void longEDoubleOcupamDuas() {
      // A especificação chama isso de "decisão infeliz", nas palavras dela.
      PoolDeConstantes pool = new PoolDeConstantes();

      int primeiro = pool.longo(1L);
      int segundo = pool.utf8("depois");

      assertThat(segundo).isEqualTo(primeiro + 2);
    }

    @Test
    @DisplayName("a mesma entrada não entra duas vezes")
    void deduplicacao() {
      // Não é economia: um verificador rigoroso recusa a classe com UTF-8
      // repetido.
      PoolDeConstantes pool = new PoolDeConstantes();

      assertThat(pool.utf8("igual")).isEqualTo(pool.utf8("igual"));
      assertThat(pool.classe("java/lang/String")).isEqualTo(pool.classe("java/lang/String"));
      // Três entradas: o texto "igual", o texto do nome da classe e a classe.
      assertThat(pool.tamanho()).isEqualTo(4);
    }

    @Test
    @DisplayName("uma referência a método traz classe e nome-e-tipo juntos")
    void referenciaTrazTudo() {
      PoolDeConstantes pool = new PoolDeConstantes();

      pool.metodo("java/lang/Math", "sqrt", "(D)D");

      // java/lang/Math, a classe, sqrt, (D)D, o nome-e-tipo e o método.
      assertThat(pool.tamanho()).isEqualTo(7);
    }

    @Test
    @DisplayName("o nome interno troca ponto por barra")
    void nomeInterno() {
      assertThat(PoolDeConstantes.nomeInterno("java.lang.String")).isEqualTo("java/lang/String");
    }
  }

  @Nested
  @DisplayName("contagem de posições de variável")
  class Posicoes {

    @Test
    @DisplayName("double e long contam dois, o resto conta um")
    void contagem() {
      assertThat(EscritorDeClasse.posicoesDosArgumentos("()V")).isZero();
      assertThat(EscritorDeClasse.posicoesDosArgumentos("(I)V")).isEqualTo(1);
      assertThat(EscritorDeClasse.posicoesDosArgumentos("(D)D")).isEqualTo(2);
      assertThat(EscritorDeClasse.posicoesDosArgumentos("(DD)D")).isEqualTo(4);
      assertThat(EscritorDeClasse.posicoesDosArgumentos("(J)V")).isEqualTo(2);
      assertThat(EscritorDeClasse.posicoesDosArgumentos("(IDJ)V")).isEqualTo(5);
    }

    @Test
    @DisplayName("vetor e objeto contam um, com qualquer dimensão")
    void referenciasContamUm() {
      assertThat(EscritorDeClasse.posicoesDosArgumentos("([D)D")).isEqualTo(1);
      assertThat(EscritorDeClasse.posicoesDosArgumentos("([[[D)D")).isEqualTo(1);
      assertThat(EscritorDeClasse.posicoesDosArgumentos("(Ljava/lang/String;)V")).isEqualTo(1);
      assertThat(EscritorDeClasse.posicoesDosArgumentos("([Ljava/lang/String;I)V")).isEqualTo(2);
    }

    @Test
    @DisplayName("descritor malformado reclama")
    void malformado() {
      assertThatThrownBy(() -> EscritorDeClasse.posicoesDosArgumentos("sem parênteses"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("a pilha declarada")
  class Pilha {

    @Test
    @DisplayName("um double ocupa dois lugares")
    void doubleOcupaDois() {
      Codigo codigo = new Codigo(new PoolDeConstantes(), 0);

      codigo.constante(1.5);

      assertThat(codigo.pilhaAtual()).isEqualTo(2);
      assertThat(codigo.pilhaMaxima()).isEqualTo(2);
    }

    @Test
    @DisplayName("a soma consome dois valores e deixa um")
    void somaConsome() {
      Codigo codigo = new Codigo(new PoolDeConstantes(), 0);

      codigo.constante(1).constante(2).somar();

      assertThat(codigo.pilhaMaxima()).isEqualTo(4);
      assertThat(codigo.pilhaAtual()).isEqualTo(2);
    }

    @Test
    @DisplayName("o pico é guardado mesmo depois de a pilha baixar")
    void picoEGuardado() {
      // É o pico que vai no atributo Code, não a altura final.
      Codigo codigo = new Codigo(new PoolDeConstantes(), 0);

      codigo.constante(1).constante(2).constante(3).somar().somar();

      assertThat(codigo.pilhaMaxima()).isEqualTo(6);
      assertThat(codigo.pilhaAtual()).isEqualTo(2);
    }

    @Test
    @DisplayName("consumir valor que não existe é pego na montagem")
    void pilhaNegativa() {
      Codigo codigo = new Codigo(new PoolDeConstantes(), 0);

      assertThatThrownBy(codigo::somar)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("negativa");
    }
  }

  @Nested
  @DisplayName("a JVM como juiz")
  class JvmComoJuiz {

    @Test
    @DisplayName("carrega e roda uma classe montada na mão")
    void carregaERoda() throws Exception {
      EscritorDeClasse escritor = new EscritorDeClasse("br.com.conde.bytecode.gerado.Somador");

      escritor.construtorVazio();

      Codigo codigo = escritor.metodo(EscritorDeClasse.PUBLICA | EscritorDeClasse.ESTATICA, "somar", "(DD)D");

      codigo.carregarDouble(0).carregarDouble(2).somar().retornarDouble();

      Class<?> classe = new CarregadorEmMemoria().definir(escritor.nomeInterno().replace('/', '.'), escritor.bytes());
      Object resultado = classe.getMethod("somar", double.class, double.class).invoke(null, 2.5, 4.0);

      assertThat(resultado).isEqualTo(6.5);
    }

    @Test
    @DisplayName("declarar pilha a menos é recusado — mas só na ligação")
    void pilhaAMenosERecusada() {
      // Este é o ponto do projeto inteiro: o verificador confere o que a
      // classe declara, e não o que ela faz. Com a pilha mentida, a classe
      // nem carrega — o erro vem antes de a primeira instrução rodar.
      EscritorDeClasse escritor = new EscritorDeClasse("br.com.conde.bytecode.gerado.Mentiroso");

      escritor.construtorVazio();

      Codigo codigo = escritor.metodo(EscritorDeClasse.PUBLICA | EscritorDeClasse.ESTATICA, "obter", "()D");

      codigo.constante(1).constante(2).somar().retornarDouble();

      assertThat(codigo.pilhaCalculada()).isEqualTo(4);

      codigo.declararPilhaMaxima(2);

      Class<?> classe = new CarregadorEmMemoria().definir("br.com.conde.bytecode.gerado.Mentiroso", escritor.bytes());

      // O defineClass passa: ele só confere o formato. O verificador de
      // bytecode é preguiçoso e só roda na ligação, no primeiro uso de
      // verdade — é por isso que uma classe gerada errada pode "carregar bem"
      // e explodir minutos depois.
      assertThat(classe.getName()).endsWith("Mentiroso");

      assertThatThrownBy(() -> classe.getDeclaredConstructor().newInstance())
          .isInstanceOf(VerifyError.class)
          .hasMessageContaining("Operand stack overflow");
    }

    @Test
    @DisplayName("método sem corpo é pego antes de virar bytes")
    void metodoSemCorpo() {
      EscritorDeClasse escritor = new EscritorDeClasse("br.com.conde.bytecode.gerado.Vazio");

      escritor.metodo(EscritorDeClasse.PUBLICA, "nada", "()V");

      assertThatThrownBy(escritor::bytes).isInstanceOf(IllegalStateException.class).hasMessageContaining("sem corpo");
    }
  }

  @Nested
  @DisplayName("o javap como juiz")
  class JavapComoJuiz {

    @Test
    @DisplayName("desmonta a classe gerada e mostra as instruções esperadas")
    void javapDesmonta(@TempDir Path pasta) throws Exception {
      String javap = ferramentaDoJdk("javap");

      assumeTrue(javap != null, "javap não encontrado neste ambiente");

      Path arquivo = pasta.resolve("Gerada.class");

      Files.write(arquivo, Compilador.montarClasse("Gerada", "(a + b) * 2"));

      String saida = rodar(javap, "-c", "-p", arquivo.toString());

      // Se o javap consegue desmontar, a estrutura do arquivo está correta
      // segundo a ferramenta oficial — não segundo o meu próprio leitor.
      assertThat(saida).contains("public class Gerada implements br.com.conde.bytecode.Expressao");
      assertThat(saida).contains("public double calcular(double[])");
      assertThat(saida).contains("daload");
      assertThat(saida).contains("dadd");
      assertThat(saida).contains("dmul");
      assertThat(saida).contains("dreturn");
      assertThat(saida).contains("double 2.0d");
    }

    @Test
    @DisplayName("a chamada a Math.pow aparece como invokestatic")
    void javapMostraAChamada(@TempDir Path pasta) throws Exception {
      String javap = ferramentaDoJdk("javap");

      assumeTrue(javap != null, "javap não encontrado neste ambiente");

      Path arquivo = pasta.resolve("ComPotencia.class");

      Files.write(arquivo, Compilador.montarClasse("ComPotencia", "raiz(x) ^ 2"));

      String saida = rodar(javap, "-c", "-p", arquivo.toString());

      assertThat(saida).contains("invokestatic");
      assertThat(saida).contains("java/lang/Math.sqrt:(D)D");
      assertThat(saida).contains("java/lang/Math.pow:(DD)D");
    }

    /** O caminho de uma ferramenta do JDK que está rodando este teste. */
    private String ferramentaDoJdk(String nome) {
      Path base = Path.of(System.getProperty("java.home"), "bin");

      for (String candidato : new String[] {nome + ".exe", nome}) {
        Path caminho = base.resolve(candidato);

        if (Files.isExecutable(caminho)) {
          return caminho.toString();
        }
      }

      return null;
    }

    private String rodar(String... comando) throws IOException, InterruptedException {
      Process processo = new ProcessBuilder(comando).redirectErrorStream(true).start();

      String saida = new String(processo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

      assertThat(processo.waitFor(30, TimeUnit.SECONDS)).as("o comando não terminou").isTrue();
      assertThat(processo.exitValue()).as("saída do comando:%n%s", saida).isZero();

      return saida;
    }
  }
}
