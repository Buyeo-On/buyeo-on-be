package com.buyeoon.member.auth.social;

@FunctionalInterface
interface AppleClientSecretProvider {

	String generate();
}
