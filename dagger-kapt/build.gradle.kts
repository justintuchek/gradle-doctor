plugins {
  kotlin("jvm")
  kotlin("kapt")
}

dependencies {
  implementation("com.google.dagger:dagger:2.44.2")
  kapt("com.google.dagger:dagger-compiler:2.44.2")

  implementation("com.google.auto.value:auto-value-annotations:1.10.1.1.1")
  kapt("com.google.auto.value:auto-value:1.10")
}
